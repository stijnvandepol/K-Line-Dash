package nl.stijnvdpol.klinedash.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import nl.stijnvdpol.klinedash.core.transport.TransportException
import java.util.UUID

/*
 * The GATT operations of a BLE session, one at a time: each one puts a fresh result slot into the
 * session's [GattCallback], starts the operation and waits (with a timeout) for the callback to
 * complete it. A dropped link fails every waiting slot (GattCallback.failAll). The callers hold
 * the BLUETOOTH_CONNECT permission (checked by BleTransport.connect).
 */

private const val TAG = "KLineDash"

private const val REQUESTED_MTU = 185
private const val MTU_TIMEOUT_MS = 3_000L
private const val SERVICES_TIMEOUT_MS = 10_000L
private const val DISCOVERY_ATTEMPTS = 2
private const val DISCOVERY_RETRY_DELAY_MS = 500L
private const val DESCRIPTOR_TIMEOUT_MS = 5_000L
private const val WRITE_TIMEOUT_MS = 5_000L
private const val MAX_WRITE_RETRIES = 60
private const val WRITE_RETRY_DELAY_MS = 10L

private val CCCD_UUID: UUID = uuid16(0x2902)

/** Waits for [d]; a timeout becomes a [TransportException] that says which step ([what]) hung. */
internal suspend fun <T> awaitStep(d: CompletableDeferred<T>, timeoutMs: Long, what: String): T =
    try {
        withTimeout(timeoutMs) { d.await() }
    } catch (e: TimeoutCancellationException) {
        throw TransportException("Timed out while $what", e)
    }

/** Asks for a larger MTU; plenty of clones never answer or reject, and the default 20-byte chunk still works. */
@SuppressLint("MissingPermission")
internal suspend fun requestMtu(g: BluetoothGatt, h: GattCallback) {
    val d = CompletableDeferred<Int>()
    h.mtuResult = d
    try {
        if (g.requestMtu(REQUESTED_MTU)) {
            try {
                withTimeout(MTU_TIMEOUT_MS) { d.await() }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "MTU request timed out, staying at the default chunk size")
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
internal suspend fun discoverServices(g: BluetoothGatt, h: GattCallback) {
    var lastStatus: Int? = null
    for (attempt in 1..DISCOVERY_ATTEMPTS) {
        val status = discoverOnce(g, h)
        lastStatus = status
        if (status == BluetoothGatt.GATT_SUCCESS) return
        val outcome = if (status == null) "timed out" else "failed (GATT status $status)"
        val found = g.services.joinToString { it.uuid.toString() }
        if (findBleUartCandidates(g, knownOnly = true).isNotEmpty()) {
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
@SuppressLint("MissingPermission")
private suspend fun discoverOnce(g: BluetoothGatt, h: GattCallback): Int? {
    val d = CompletableDeferred<Int>()
    h.servicesResult = d
    try {
        if (!g.discoverServices()) throw TransportException("Could not read the services of the adapter")
        return withTimeoutOrNull(SERVICES_TIMEOUT_MS) { d.await() }
    } finally {
        h.servicesResult = null
    }
}

@SuppressLint("MissingPermission")
internal suspend fun enableNotifications(g: BluetoothGatt, h: GattCallback, notify: BluetoothGattCharacteristic) {
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

/** Writes one chunk of at most MTU - 3 bytes with the write [type] the characteristic supports. */
internal suspend fun writeChunk(g: BluetoothGatt, h: GattCallback, c: BluetoothGattCharacteristic, chunk: ByteArray, type: Int) {
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

/** API 33 replaced value-on-characteristic with an explicit value argument. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun startWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, type: Int): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
    } else {
        c.writeType = type
        c.value = value
        g.writeCharacteristic(c)
    }

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun writeDescriptorCompat(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
    } else {
        d.value = value
        g.writeDescriptor(d)
    }
