package nl.stijnvdpol.klinedash.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import nl.stijnvdpol.klinedash.core.transport.PromptBuffer
import nl.stijnvdpol.klinedash.core.transport.Transport
import nl.stijnvdpol.klinedash.core.transport.TransportException
import nl.stijnvdpol.klinedash.core.transport.TransportKind
import nl.stijnvdpol.klinedash.core.transport.TransportTimeoutException
import java.util.UUID

/**
 * ELM327 over Bluetooth Low Energy. Cheap BLE clones expose a "serial over GATT" service whose
 * layout differs per vendor; [findBleUart] picks the characteristics after service discovery.
 *
 * Flow control: Android allows exactly one GATT operation at a time, so every operation sets a
 * [CompletableDeferred] that the callback completes, and writes are serialised by [opMutex].
 * Writes are split into (MTU - 3)-byte chunks. Replies arrive as notifications of at most MTU-3
 * bytes and are reassembled through [PromptBuffer], exactly like the stream transports.
 *
 * Callbacks run on a Binder thread, never on the caller's coroutine, so all state they touch is
 * either volatile or guarded by [lock].
 */
@SuppressLint("MissingPermission") // checked explicitly via requireBtConnectPermission
class BleTransport(
    context: Context,
    private val address: String,
) : Transport {

    private val appContext = context.applicationContext

    override val kind: TransportKind = TransportKind.BLE

    @Volatile private var deviceName: String? = null

    override val description: String
        get() = "${deviceName ?: DEFAULT_ADAPTER_NAME} ($address)"

    override val isConnected: Boolean get() = connected

    /** Guards [promptBuffer] and the connection fields shared with GATT callbacks. */
    private val lock = Any()
    private val promptBuffer = PromptBuffer()
    private val connectMutex = Mutex()
    private val opMutex = Mutex()

    @Volatile private var connected = false
    @Volatile private var channel: Channel<String>? = null
    @Volatile private var active: GattHandler? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

    /** Payload bytes per write/notification; ATT header is 3 bytes. 20 = default MTU 23. */
    @Volatile private var maxChunk = DEFAULT_CHUNK

    // ---------------------------------------------------------------- Transport

    override suspend fun connect() {
        connectMutex.withLock {
            if (connected) return
            teardown() // leftovers of a previous failed/dropped session

            requireBtConnectPermission(appContext)
            val adapter = bluetoothAdapter(appContext) ?: throw TransportException(ADAPTER_UNAVAILABLE_MESSAGE)
            if (!adapter.isEnabled) {
                throw TransportException(BLUETOOTH_OFF_MESSAGE)
            }
            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                throw TransportException(invalidAddressMessage(address))
            }
            val device = adapter.getRemoteDevice(address)
            deviceName = device.nameOrNull()

            val ch = Channel<String>(Channel.UNLIMITED)
            val handler = GattHandler(ch)
            synchronized(lock) {
                promptBuffer.clear()
                channel = ch
                active = handler
                maxChunk = DEFAULT_CHUNK
                writeChar = null
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

            try {
                val g = device.connectGatt(appContext, false, handler, BluetoothDevice.TRANSPORT_LE)
                    ?: throw TransportException("Could not start a BLE connection")
                // close() may have run teardown() while connectGatt() was being called; it then
                // never saw this client, so release it here.
                val accepted = synchronized(lock) { (active === handler).also { if (it) gatt = g } }
                if (!accepted) {
                    g.close()
                    throw TransportException("The connection to the adapter was closed")
                }
                awaitStep(handler.connectResult, CONNECT_TIMEOUT_MS, "connecting")
                // Some stacks return GATT_ERROR/133 when discovering immediately after connect.
                delay(POST_CONNECT_DELAY_MS)

                discoverServices(g, handler)
                Log.i(TAG, "BLE services: " + g.services.joinToString { it.uuid.toString() })
                val uart = findBleUart(g)
                    ?: throw TransportException(
                        "This device has no serial BLE service (FFE0/FFF0/18F0/Nordic UART). " +
                            "It is probably a Bluetooth Classic adapter: choose Bluetooth Classic instead.",
                    )
                writeChar = uart.write
                // Chosen from the characteristic's own properties: the Konnwei KW905's FFF2 only
                // supports write-without-response (props 0x04), a WRITE_TYPE_DEFAULT write fails there.
                writeType = if (uart.write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                handler.notifyUuid = uart.notify.uuid
                enableNotifications(g, handler, uart.notify)
                // Last, because it is optional (it only enlarges chunks): the stack queues GATT
                // operations, so a clone that is slow to answer the MTU exchange used to push the
                // mandatory discovery/CCCD steps into their timeouts.
                requestMtu(g, handler)

                synchronized(lock) {
                    // gatt !== g: close() tore the session down between two steps.
                    if (handler.disconnected.isCompleted || active !== handler || gatt !== g) {
                        throw TransportException("The BLE connection dropped while connecting")
                    }
                    connected = true
                }
                Log.i(TAG, "BLE ready: notify=${uart.notify.uuid} write=${uart.write.uuid} chunk=$maxChunk type=$writeType")
            } catch (e: Throwable) {
                teardown()
                throw when (e) {
                    is CancellationException, is TransportException, is SecurityException -> e
                    else -> TransportException(e.message ?: "BLE connection failed", e)
                }
            }
        }
    }

    override suspend fun write(data: String) {
        val g = gatt
        val c = writeChar
        val h = active
        if (!connected || g == null || c == null || h == null) {
            throw TransportException("Not connected to the adapter")
        }
        val bytes = data.toByteArray(Charsets.US_ASCII)
        opMutex.withLock {
            var offset = 0
            while (offset < bytes.size) {
                if (!connected) throw TransportException("The BLE connection dropped")
                val end = minOf(offset + maxChunk, bytes.size)
                writeChunk(g, h, c, bytes.copyOfRange(offset, end))
                offset = end
            }
        }
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        val ch = channel ?: throw TransportException("Not connected to the adapter")
        // A channel closed with a cause rethrows that TransportException straight from receive().
        return try {
            withTimeout(timeoutMs) { ch.receive() }
        } catch (e: TimeoutCancellationException) {
            val partial = synchronized(lock) { promptBuffer.pending() }
            throw TransportTimeoutException(partial, timeoutMs)
        } catch (e: ClosedReceiveChannelException) {
            throw TransportException("The connection to the adapter was closed", e)
        }
    }

    override fun discardPending() {
        val ch = channel ?: return
        synchronized(lock) {
            while (ch.tryReceive().isSuccess) { /* drop */ }
            promptBuffer.clear()
        }
    }

    override suspend fun close() {
        // Deliberately not under connectMutex: closing during a slow connect aborts it promptly
        // (pending GATT steps fail, connect() then runs its own idempotent teardown).
        teardown()
    }

    // ---------------------------------------------------------------- GATT steps

    private suspend fun requestMtu(g: BluetoothGatt, h: GattHandler) {
        val d = CompletableDeferred<Int>()
        h.mtuResult = d
        try {
            if (g.requestMtu(REQUESTED_MTU)) {
                // Plenty of clones never answer or reject; the default 20-byte chunk still works.
                try {
                    withTimeout(MTU_TIMEOUT_MS) { d.await() }
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "MTU request timed out, staying at $maxChunk-byte chunks")
                }
            }
        } finally {
            h.mtuResult = null
        }
    }

    /**
     * Full service discovery can stall on cheap adapters: the Konnwei KW905 answers the first
     * handles (1800/1801/180A) and then the walk fails or never ends, on an ESP32 and on Windows
     * alike, while its FFF0 service is perfectly usable (docs/research/esp32-obd2-lessons.md).
     * So a failed or timed-out discovery is not fatal when the services found so far already hold
     * a usable known UART; otherwise discovery is retried once before giving up.
     */
    private suspend fun discoverServices(g: BluetoothGatt, h: GattHandler) {
        var lastStatus: Int? = null
        for (attempt in 1..DISCOVERY_ATTEMPTS) {
            val status = discoverOnce(g, h)
            lastStatus = status
            if (status == BluetoothGatt.GATT_SUCCESS) return
            val outcome = if (status == null) "timed out" else "failed (GATT status $status)"
            val found = g.services.joinToString { it.uuid.toString() }
            if (findKnownBleUart(g) != null) {
                Log.w(TAG, "BLE service discovery $outcome, but a known UART service is present ($found); using it")
                return
            }
            Log.w(TAG, "BLE service discovery $outcome (attempt $attempt of $DISCOVERY_ATTEMPTS), services so far: $found")
            if (attempt < DISCOVERY_ATTEMPTS) delay(DISCOVERY_RETRY_DELAY_MS)
        }
        throw TransportException(
            if (lastStatus == null) "Timed out while discovering BLE services" else "Reading the adapter services failed (GATT status $lastStatus)",
        )
    }

    /** One discovery round. Returns the GATT status, or null when the callback never came. */
    private suspend fun discoverOnce(g: BluetoothGatt, h: GattHandler): Int? {
        val d = CompletableDeferred<Int>()
        h.servicesResult = d
        try {
            if (!g.discoverServices()) throw TransportException("Could not read the services of the adapter")
            return withTimeoutOrNull(SERVICES_TIMEOUT_MS) { d.await() }
        } finally {
            h.servicesResult = null
        }
    }

    private suspend fun enableNotifications(g: BluetoothGatt, h: GattHandler, notify: BluetoothGattCharacteristic) {
        if (!g.setCharacteristicNotification(notify, true)) {
            throw TransportException("Could not enable notifications from the adapter")
        }
        // Without the CCCD write the peripheral never sends; a few clones lack the descriptor but
        // push data after setCharacteristicNotification alone, so a missing CCCD is tolerated.
        val cccd = notify.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            Log.w(TAG, "No CCCD descriptor on ${notify.uuid}, continuing without it")
            return
        }
        val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val d = CompletableDeferred<Int>()
        h.descriptorResult = d
        try {
            if (!writeDescriptorCompat(g, cccd, value)) {
                throw TransportException("Could not enable notifications from the adapter (CCCD)")
            }
            val status = awaitStep(d, DESCRIPTOR_TIMEOUT_MS, "enabling notifications")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                throw TransportException("Enabling notifications failed (GATT status $status)")
            }
        } finally {
            h.descriptorResult = null
        }
    }

    private suspend fun writeChunk(g: BluetoothGatt, h: GattHandler, c: BluetoothGattCharacteristic, chunk: ByteArray) {
        val type = writeType
        if (type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            // Not waiting for onCharacteristicWrite (unreliable for write-without-response). The
            // stack rejects a new write while the previous one is in flight, so retry briefly.
            var attempts = 0
            while (!startWrite(g, c, chunk, type)) {
                if (++attempts >= MAX_WRITE_RETRIES) throw TransportException("Writing to the adapter failed")
                delay(WRITE_RETRY_DELAY_MS)
            }
        } else {
            val d = CompletableDeferred<Int>()
            h.writeResult = d
            try {
                if (!startWrite(g, c, chunk, type)) throw TransportException("Writing to the adapter failed")
                val status = awaitStep(d, WRITE_TIMEOUT_MS, "writing to the adapter")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    throw TransportException("Writing to the adapter failed (GATT status $status)")
                }
            } finally {
                h.writeResult = null
            }
        }
    }

    // ---------------------------------------------------------------- API-level shims

    /** API 33 replaced value-on-characteristic with an explicit value argument. */
    @Suppress("DEPRECATION")
    private fun startWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = type
            c.value = value
            g.writeCharacteristic(c)
        }

    @Suppress("DEPRECATION")
    private fun writeDescriptorCompat(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }

    // ---------------------------------------------------------------- helpers

    private suspend fun <T> awaitStep(d: CompletableDeferred<T>, timeoutMs: Long, what: String): T =
        try {
            withTimeout(timeoutMs) { d.await() }
        } catch (e: TimeoutCancellationException) {
            throw TransportException("Timed out while $what", e)
        }

    /** Idempotent. Runs non-cancellable so a cancelled caller still releases the GATT client. */
    private suspend fun teardown() {
        withContext(NonCancellable) {
            val (g, h) = synchronized(lock) {
                connected = false
                val pair = gatt to active
                gatt = null
                writeChar = null
                channel?.close(TransportException("The connection to the adapter was closed"))
                pair
            }
            h?.failAll(TransportException("The connection to the adapter was closed"))
            if (g != null) {
                try {
                    g.disconnect()
                    // Closing before the disconnect callback can leave the link up on some stacks.
                    if (h != null) withTimeoutOrNull(DISCONNECT_WAIT_MS) { h.disconnected.await() }
                } catch (e: Exception) {
                    Log.w(TAG, "BLE disconnect failed", e)
                }
                try {
                    g.close()
                } catch (e: Exception) {
                    Log.w(TAG, "BLE close failed", e)
                }
            }
            synchronized(lock) { if (active === h) active = null }
        }
    }

    // ---------------------------------------------------------------- callbacks

    private inner class GattHandler(private val ch: Channel<String>) : BluetoothGattCallback() {
        val connectResult = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Unit>()

        @Volatile var mtuResult: CompletableDeferred<Int>? = null
        @Volatile var servicesResult: CompletableDeferred<Int>? = null
        @Volatile var descriptorResult: CompletableDeferred<Int>? = null
        @Volatile var writeResult: CompletableDeferred<Int>? = null
        @Volatile var notifyUuid: UUID? = null

        private val isCurrent: Boolean get() = active === this

        fun failAll(ex: TransportException) {
            connectResult.completeExceptionally(ex)
            mtuResult?.completeExceptionally(ex)
            servicesResult?.completeExceptionally(ex)
            descriptorResult?.completeExceptionally(ex)
            writeResult?.completeExceptionally(ex)
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!isCurrent) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        connectResult.complete(Unit)
                    } else {
                        connectResult.completeExceptionally(TransportException("BLE connection failed (GATT status $status)"))
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "BLE disconnected, status=$status")
                    disconnected.complete(Unit)
                    val ex = TransportException(
                        if (connected) {
                            "The BLE connection to the adapter dropped (status $status)"
                        } else {
                            // Typically 133 / 62 while connecting: adapter asleep, out of range or busy.
                            "BLE connection failed (GATT status $status). Is the adapter in range and not already connected to a phone?"
                        },
                    )
                    synchronized(lock) {
                        connected = false
                        ch.close(ex)
                    }
                    failAll(ex)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!isCurrent) return
            if (status == BluetoothGatt.GATT_SUCCESS) maxChunk = (mtu - ATT_HEADER_BYTES).coerceAtLeast(DEFAULT_CHUNK)
            mtuResult?.complete(status)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isCurrent) return
            servicesResult?.complete(status)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!isCurrent) return
            descriptorResult?.complete(status)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (!isCurrent) return
            writeResult?.complete(status)
        }

        /** API 33+: value delivered as a parameter (the characteristic's cached value may be stale). */
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            deliver(characteristic.uuid, value)
        }

        /** Pre-33 path; on API 33+ the platform calls the overload above instead. */
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            deliver(characteristic.uuid, characteristic.value)
        }

        private fun deliver(uuid: UUID, value: ByteArray?) {
            if (value == null || value.isEmpty()) return
            if (uuid != notifyUuid) return
            synchronized(lock) {
                if (!isCurrent) return
                for (response in promptBuffer.feed(value)) ch.trySend(response)
            }
        }
    }

    private companion object {
        const val TAG = "KLineDash"

        const val REQUESTED_MTU = 185
        const val DEFAULT_CHUNK = 20
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val POST_CONNECT_DELAY_MS = 250L
        const val MTU_TIMEOUT_MS = 3_000L
        const val SERVICES_TIMEOUT_MS = 10_000L
        const val DISCOVERY_ATTEMPTS = 2
        const val DISCOVERY_RETRY_DELAY_MS = 500L
        const val DESCRIPTOR_TIMEOUT_MS = 5_000L
        const val WRITE_TIMEOUT_MS = 5_000L
        const val DISCONNECT_WAIT_MS = 1_000L
        const val MAX_WRITE_RETRIES = 60
        const val WRITE_RETRY_DELAY_MS = 10L

        /** Bytes of every ATT packet taken by the opcode and handle, so payload = MTU - 3. */
        const val ATT_HEADER_BYTES = 3

        val CCCD_UUID: UUID = uuid16(0x2902)
    }
}
