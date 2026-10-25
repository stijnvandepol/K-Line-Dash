package nl.stijnvdpol.klinedash.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import nl.stijnvdpol.klinedash.core.transport.LOCKED_ADAPTER_MESSAGE
import nl.stijnvdpol.klinedash.core.transport.PromptBuffer
import nl.stijnvdpol.klinedash.core.transport.PromptOverflowException
import nl.stijnvdpol.klinedash.core.transport.Transport
import nl.stijnvdpol.klinedash.core.transport.TransportException
import nl.stijnvdpol.klinedash.core.transport.TransportKind
import nl.stijnvdpol.klinedash.core.transport.TransportTimeoutException
import nl.stijnvdpol.klinedash.core.transport.UnsupportedAdapterException

/**
 * ELM327 over Bluetooth Low Energy. Cheap BLE clones expose a "serial over GATT" service whose
 * layout differs per vendor; [findBleUartCandidates] lists the likely characteristic pairs after
 * service discovery and [selectUart] keeps the first one that answers an ELM327 probe ("ATI").
 * Once connected the transport asks for the high connection priority: the default connection
 * interval (30-50 ms) would otherwise cap the request rate on a fast CAN car.
 *
 * Flow control: Android allows exactly one GATT operation at a time, so every operation waits for
 * its callback (see [GattCallback] and BleGattSteps.kt), and writes are serialised by [opMutex].
 * Writes are split into (MTU - 3)-byte chunks. Replies arrive as notifications of at most MTU-3
 * bytes and are reassembled through [PromptBuffer], exactly like the stream transports, and so is
 * the queue of finished replies: [RESPONSE_QUEUE_CAPACITY] of them, the oldest dropped beyond that
 * (one request has one reply, however many notifications it takes, and the session discards stale
 * ones before every command). A [PromptOverflowException] from the buffer ends the session like a
 * lost link.
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
    @Volatile private var active: GattCallback? = null
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

            val ch = Channel<String>(RESPONSE_QUEUE_CAPACITY, BufferOverflow.DROP_OLDEST)
            val handler = GattCallback(ch, events)
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
                val uart = openUart(g, handler)
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

    /**
     * The GATT steps of a connect once the client exists: wait for the link, find and probe the
     * serial characteristics, then tune the connection. Returns the UART that answered.
     */
    private suspend fun openUart(g: BluetoothGatt, handler: GattCallback): BleUart {
        awaitStep(handler.connectResult, CONNECT_TIMEOUT_MS, "connecting")
        // Some stacks return GATT_ERROR/133 when discovering immediately after connect.
        delay(POST_CONNECT_DELAY_MS)

        discoverServices(g, handler)
        Log.i(TAG, "BLE services: " + g.services.joinToString { it.uuid.toString() })
        val candidates = findBleUartCandidates(g)
        if (candidates.isEmpty()) {
            throw UnsupportedAdapterException(
                "This device has no serial BLE service. " +
                    "It is probably a Bluetooth Classic adapter: choose Bluetooth Classic instead.",
            )
        }
        val uart = selectUart(g, handler, candidates)
        if (!g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)) {
            Log.w(TAG, "BLE high connection priority was not accepted")
        }
        // Last, because it is optional (it only enlarges chunks): the stack queues GATT
        // operations, so a clone that is slow to answer the MTU exchange used to push the
        // mandatory discovery/CCCD steps into their timeouts.
        requestMtu(g, handler)
        return uart
    }

    override suspend fun write(data: String) {
        val g = gatt
        val c = writeChar
        val h = active
        if (!connected || g == null || c == null || h == null) {
            throw TransportException("Not connected to the adapter")
        }
        sendBytes(g, h, c, data.toByteArray(Charsets.US_ASCII))
    }

    private suspend fun sendBytes(g: BluetoothGatt, h: GattCallback, c: BluetoothGattCharacteristic, bytes: ByteArray) {
        opMutex.withLock {
            var offset = 0
            while (offset < bytes.size) {
                // gatt is cleared by teardown(), so this also notices a close() between two chunks.
                if (gatt !== g) throw TransportException("The BLE connection dropped")
                val end = minOf(offset + maxChunk, bytes.size)
                writeChunk(g, h, c, bytes.copyOfRange(offset, end), writeType)
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

    /**
     * The first of [candidates] that answers an ELM327 probe, left activated. Known layouts come
     * first, so a normal adapter costs one probe. If none answers but a known layout is among
     * them, that one is used anyway and the session's own ATZ attempts decide (an adapter that is
     * merely slow to wake is not "locked"); with only guessed pairs the adapter is declared
     * unusable.
     */
    private suspend fun selectUart(g: BluetoothGatt, h: GattCallback, candidates: List<BleUart>): BleUart {
        for (candidate in candidates) {
            if (answersProbe(g, h, candidate)) return candidate
            Log.w(TAG, "No ELM327 answer on notify=${candidate.notify.uuid} write=${candidate.write.uuid}")
        }
        val fallback = candidates.firstOrNull { it.isKnownProfile }
            ?: throw UnsupportedAdapterException(LOCKED_ADAPTER_MESSAGE)
        activate(g, h, fallback)
        return fallback
    }

    /** Activates [uart], sends "ATI" and reports whether any prompt came back within [PROBE_TIMEOUT_MS]. */
    private suspend fun answersProbe(g: BluetoothGatt, h: GattCallback, uart: BleUart): Boolean {
        val ch = channel ?: throw TransportException("The connection to the adapter was closed")
        try {
            activate(g, h, uart)
            discardPending()
            sendBytes(g, h, uart.write, PROBE_COMMAND.toByteArray(Charsets.US_ASCII))
            val answered = withTimeoutOrNull(PROBE_TIMEOUT_MS) { ch.receive() } != null
            discardPending()
            return answered
        } catch (e: TransportException) {
            // A failing CCCD write or write only disqualifies this pair; a dropped link ends it all.
            if (gatt !== g || h.disconnected.isCompleted) throw e
            Log.w(TAG, "BLE probe on ${uart.notify.uuid} failed: ${e.message}")
            return false
        }
    }

    private suspend fun activate(g: BluetoothGatt, h: GattCallback, uart: BleUart) {
        writeChar = uart.write
        // Chosen from the characteristic's own properties: the Konnwei KW905's FFF2 only
        // supports write-without-response (props 0x04), a WRITE_TYPE_DEFAULT write fails there.
        writeType = if (uart.write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        h.notifyUuid = uart.notify.uuid
        enableNotifications(g, h, uart.notify)
    }

    // ---------------------------------------------------------------- teardown

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

    /** Binder-thread events of the current session's [GattCallback]. */
    private val events = object : GattEvents {
        override fun isCurrent(callback: GattCallback): Boolean = active === callback

        override fun onMtuChanged(mtu: Int) {
            maxChunk = (mtu - ATT_HEADER_BYTES).coerceAtLeast(DEFAULT_CHUNK)
        }

        override fun onDisconnected(callback: GattCallback, status: Int): TransportException {
            Log.w(TAG, "BLE disconnected, status=$status")
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
                callback.responses.close(ex)
            }
            return ex
        }

        override fun onNotification(callback: GattCallback, value: ByteArray) {
            synchronized(lock) {
                if (active !== callback) return
                try {
                    for (response in promptBuffer.feed(value)) callback.responses.trySend(response)
                } catch (e: PromptOverflowException) {
                    // Not an ELM327, or a broken one: the connection manager sees the transport is no longer connected and reconnects.
                    Log.w(TAG, "BLE adapter overflowed the response buffer: ${e.message}")
                    connected = false
                    callback.responses.close(e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "KLineDash"

        const val DEFAULT_CHUNK = 20

        /** The same bound as the stream transports of obd-core (StreamTransport.RESPONSE_QUEUE_CAPACITY, internal there). */
        const val RESPONSE_QUEUE_CAPACITY = 64
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val POST_CONNECT_DELAY_MS = 250L

        /** The cheapest command every ELM327 and clone answers with at least a prompt. Not a bare CR: that repeats the last command. */
        const val PROBE_COMMAND = "ATI\r"
        const val PROBE_TIMEOUT_MS = 2_500L
        const val DISCONNECT_WAIT_MS = 1_000L

        /** Bytes of every ATT packet taken by the opcode and handle, so payload = MTU - 3. */
        const val ATT_HEADER_BYTES = 3
    }
}
