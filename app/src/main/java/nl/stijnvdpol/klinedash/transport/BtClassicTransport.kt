package nl.stijnvdpol.klinedash.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import nl.stijnvdpol.klinedash.core.transport.StreamTransport
import nl.stijnvdpol.klinedash.core.transport.TransportException
import nl.stijnvdpol.klinedash.core.transport.TransportKind
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * ELM327 over Bluetooth Classic (SPP / RFCOMM). The device should be paired in the Android
 * Bluetooth settings first; many clones refuse or time out on unpaired connects.
 *
 * Connect strategy, tried in order until one works (cheap clones are picky, and head-unit
 * Bluetooth stacks differ from phones):
 *  1. `cancelDiscovery()` - an active inquiry makes connect() slow or fail outright.
 *  2. `createRfcommSocketToServiceRecord(SPP)` - the documented, secure SDP-based path.
 *  3. `createInsecureRfcommSocketToServiceRecord(SPP)` - skips link-key authentication; some clones
 *     advertise SPP but break the secure handshake.
 *  4. Reflection `createRfcommSocket(1)` - connects straight to RFCOMM channel 1 without SDP. A
 *     long-standing workaround for clones with a missing/broken SDP record. It is a non-SDK
 *     (greylisted) API and may be blocked on newer Android versions.
 *  5. Reflection `createInsecureRfcommSocket(1)` and `(2)` - the same without link-key
 *     authentication, on channel 1 and on channel 2, where a few clones put SPP (B4X and CANblue
 *     threads, docs/research/elm327-clones.md).
 *
 * `BluetoothSocket.connect()` blocks without a timeout, so it runs on its own coroutine and the
 * socket is closed when [CONNECT_TIMEOUT_MS] elapses, which makes connect() throw and frees the thread.
 */
@SuppressLint("MissingPermission") // checked explicitly via requireBtConnectPermission
class BtClassicTransport(
    context: Context,
    private val address: String,
) : StreamTransport() {

    private val appContext = context.applicationContext

    override val kind: TransportKind = TransportKind.BT_CLASSIC

    @Volatile private var deviceName: String? = null

    override val description: String
        get() = "${deviceName ?: lookupName() ?: DEFAULT_ADAPTER_NAME} ($address)"

    @Volatile private var socket: BluetoothSocket? = null

    /** Cosmetic: falls back to [DEFAULT_ADAPTER_NAME] without permission or with a bad address. */
    private fun lookupName(): String? {
        if (!hasBtConnectPermission(appContext) || !BluetoothAdapter.checkBluetoothAddress(address)) return null
        return bluetoothAdapter(appContext)?.getRemoteDevice(address)?.nameOrNull()?.also { deviceName = it }
    }

    override suspend fun openStreams(): Pair<InputStream, OutputStream> {
        requireBtConnectPermission(appContext)
        val adapter = bluetoothAdapter(appContext) ?: throw TransportException(ADAPTER_UNAVAILABLE_MESSAGE)
        if (!adapter.isEnabled) {
            throw TransportException(BLUETOOTH_OFF_MESSAGE)
        }
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            throw TransportException(invalidAddressMessage(address))
        }
        val device = adapter.getRemoteDevice(address)
        deviceName = device.name

        // Needs BLUETOOTH_SCAN on API 31+; if that is denied we simply carry on (we are not scanning).
        try {
            adapter.cancelDiscovery()
        } catch (e: SecurityException) {
            Log.w(TAG, "cancelDiscovery denied", e)
        }

        val strategies: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "secure SPP" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "insecure SPP" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            "reflection channel 1" to { createRfcommSocketReflectively(device, SECURE_CHANNEL_METHOD, 1) },
            "reflection insecure channel 1" to { createRfcommSocketReflectively(device, INSECURE_CHANNEL_METHOD, 1) },
            "reflection insecure channel 2" to { createRfcommSocketReflectively(device, INSECURE_CHANNEL_METHOD, 2) },
        )

        var lastError: Exception? = null
        for ((label, factory) in strategies) {
            val candidate = try {
                factory()
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "BT socket creation failed ($label)", e)
                lastError = e
                continue
            }
            try {
                connectWithTimeout(candidate)
                socket = candidate
                Log.i(TAG, "BT connected via $label")
                return candidate.inputStream to candidate.outputStream
            } catch (e: IOException) {
                Log.w(TAG, "BT connect failed ($label)", e)
                lastError = e
                closeQuietly(candidate)
                // Clones need a moment to drop the half-open link before accepting a new attempt.
                delay(RETRY_DELAY_MS)
            }
        }
        throw TransportException(classicConnectFailedMessage(isBonded = device.bondState == BluetoothDevice.BOND_BONDED), lastError)
    }

    /** Runs the blocking connect() off-thread and closes the socket if it takes too long. */
    private suspend fun connectWithTimeout(candidate: BluetoothSocket) {
        val result = CompletableDeferred<Unit>()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                candidate.connect()
                result.complete(Unit)
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { result.await() }
        } catch (e: TimeoutCancellationException) {
            closeQuietly(candidate) // makes the blocked connect() throw
            throw IOException("Timed out while connecting (${CONNECT_TIMEOUT_MS / 1000}s)", e)
        } catch (e: CancellationException) {
            closeQuietly(candidate)
            throw e
        } catch (e: Throwable) {
            closeQuietly(candidate)
            throw e
        }
    }

    /** Hidden `BluetoothDevice.<methodName>(int channel)`; throws if this Android version blocks or lacks it. */
    private fun createRfcommSocketReflectively(device: BluetoothDevice, methodName: String, channel: Int): BluetoothSocket {
        val method = device.javaClass.getMethod(methodName, Int::class.javaPrimitiveType)
        return method.invoke(device, channel) as BluetoothSocket
    }

    private fun closeQuietly(s: BluetoothSocket) {
        try {
            s.close()
        } catch (e: IOException) {
            // A failing close() leaves nothing to release or retry; the socket is dropped either way.
        }
    }

    override fun closeUnderlying() {
        val s = socket ?: return
        socket = null
        closeQuietly(s)
    }

    private companion object {
        const val TAG = "KLineDash"
        const val CONNECT_TIMEOUT_MS = 12_000L
        const val RETRY_DELAY_MS = 300L

        const val SECURE_CHANNEL_METHOD = "createRfcommSocket"
        const val INSECURE_CHANNEL_METHOD = "createInsecureRfcommSocket"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
