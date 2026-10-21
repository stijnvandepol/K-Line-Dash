package nl.stijnvdpol.klinedash.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

private const val TAG = "KLineDash"

/** Transport technology a Bluetooth device supports, as reported by the stack. */
enum class BtDeviceType { CLASSIC, LE, DUAL, UNKNOWN }

/**
 * A Bluetooth device the user can pick as OBD adapter.
 *
 * @property name display name; "(naamloos)" when the device does not advertise one
 * @property looksLikeObd name matches a known ELM327/OBD adapter brand (heuristic only)
 */
data class BtDeviceInfo(
    val name: String,
    val address: String,
    val type: BtDeviceType,
    val looksLikeObd: Boolean,
    val rssi: Int? = null,
)

private val OBD_NAME_HINTS = listOf(
    "OBD", "ELM", "V-LINK", "VLINK", "VGATE", "ICAR", "KONNWEI", "VIECAR",
    "VEEPEAK", "OBDLINK", "CAN BUS", "BLUEDRIVER",
)

internal fun nameLooksLikeObd(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    val upper = name.uppercase()
    return OBD_NAME_HINTS.any { upper.contains(it) }
}

/** Runtime permissions the settings UI must request before listing, scanning or connecting. */
fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // API 31+: with neverForLocation on BLUETOOTH_SCAN no location permission is needed.
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        // API 26-30: BLE scanning needs location. BLUETOOTH/BLUETOOTH_ADMIN are install-time only.
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

fun hasRequiredPermissions(context: Context): Boolean =
    requiredPermissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

/** True when Bluetooth connections are allowed (always true below API 31, where it is install-time). */
internal fun hasBtConnectPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

/**
 * Throws [SecurityException] with a user-presentable message when BLUETOOTH_CONNECT is missing.
 * The connection manager maps SecurityException to a non-retryable failure.
 */
internal fun requireBtConnectPermission(context: Context) {
    if (!hasBtConnectPermission(context)) {
        throw SecurityException(
            "Bluetooth-toestemming ontbreekt (BLUETOOTH_CONNECT). Geef de app toestemming bij " +
                "Instellingen > Apps > K-Line Dash > Machtigingen.",
        )
    }
}

/**
 * The system adapter, or null. Head units with a proprietary Bluetooth module (phone-call only
 * stacks) often return null here or an adapter that cannot open RFCOMM sockets for third-party apps.
 */
internal fun bluetoothAdapter(context: Context): BluetoothAdapter? =
    context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter

internal const val ADAPTER_UNAVAILABLE_MESSAGE = "Bluetooth adapter niet beschikbaar voor apps op deze head unit"

@Suppress("DEPRECATION")
private fun mapType(raw: Int): BtDeviceType = when (raw) {
    BluetoothDevice.DEVICE_TYPE_CLASSIC -> BtDeviceType.CLASSIC
    BluetoothDevice.DEVICE_TYPE_LE -> BtDeviceType.LE
    BluetoothDevice.DEVICE_TYPE_DUAL -> BtDeviceType.DUAL
    else -> BtDeviceType.UNKNOWN
}

/**
 * Devices already paired in Android's Bluetooth settings. Most ELM327 clones must be paired
 * first (PIN usually 1234 or 0000). Returns an empty list when permission or adapter is missing.
 * OBD-looking devices are sorted first.
 */
@SuppressLint("MissingPermission")
fun bondedDevices(context: Context): List<BtDeviceInfo> {
    if (!hasBtConnectPermission(context)) return emptyList()
    val adapter = bluetoothAdapter(context) ?: return emptyList()
    return try {
        adapter.bondedDevices.orEmpty()
            .map { d ->
                val rawName = try { d.name } catch (e: SecurityException) { null }
                BtDeviceInfo(
                    name = rawName?.takeIf { it.isNotBlank() } ?: "(naamloos)",
                    address = d.address,
                    type = mapType(d.type),
                    looksLikeObd = nameLooksLikeObd(rawName),
                )
            }
            .sortedWith(compareByDescending<BtDeviceInfo> { it.looksLikeObd }.thenBy { it.name.lowercase() })
    } catch (e: SecurityException) {
        Log.w(TAG, "bondedDevices: permission revoked", e)
        emptyList()
    }
}

/**
 * Scans for BLE devices for [durationMs], emitting each address once, then completes.
 * Cancelling the collector stops the scan.
 *
 * The flow throws [SecurityException] when permissions are missing and [IllegalStateException]
 * (Dutch message) when Bluetooth is off/unavailable or the scan fails to start, so collect it
 * inside try/catch or with `.catch {}`.
 *
 * Needs BLUETOOTH_SCAN on API 31+ (declare it with `neverForLocation` in the manifest) and
 * ACCESS_FINE_LOCATION on API 26-30. On 26-30 the system "Location" toggle must also be on, or the
 * scan silently returns nothing; head units often have it off.
 */
@SuppressLint("MissingPermission")
fun scanBle(context: Context, durationMs: Long = 8000): Flow<BtDeviceInfo> = callbackFlow<BtDeviceInfo> {
    if (!hasRequiredPermissions(context)) {
        close(SecurityException("Toestemming voor Bluetooth-scan ontbreekt. Geef de app toestemming in de instellingen."))
        awaitClose { }
        return@callbackFlow
    }
    val adapter = bluetoothAdapter(context)
    val scanner = if (adapter != null && adapter.isEnabled) adapter.bluetoothLeScanner else null
    if (scanner == null) {
        close(IllegalStateException(
            if (adapter == null) ADAPTER_UNAVAILABLE_MESSAGE else "Bluetooth staat uit. Zet Bluetooth aan en probeer opnieuw.",
        ))
        awaitClose { }
        return@callbackFlow
    }

    val seen = HashSet<String>()

    fun handle(result: ScanResult) {
        val device = result.device ?: return
        if (!seen.add(device.address)) return
        val rawName = result.scanRecord?.deviceName ?: try { device.name } catch (e: SecurityException) { null }
        trySend(
            BtDeviceInfo(
                name = rawName?.takeIf { it.isNotBlank() } ?: "(naamloos)",
                address = device.address,
                type = mapType(device.type),
                looksLikeObd = nameLooksLikeObd(rawName),
                rssi = result.rssi,
            ),
        )
    }

    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handle(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
            close(IllegalStateException("BLE-scan mislukt (foutcode $errorCode)"))
        }
    }

    try {
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
    } catch (e: SecurityException) {
        close(e)
        awaitClose { }
        return@callbackFlow
    } catch (e: IllegalStateException) {
        close(e)
        awaitClose { }
        return@callbackFlow
    }

    launch {
        delay(durationMs)
        channel.close()
    }

    awaitClose {
        try {
            scanner.stopScan(callback)
        } catch (e: Exception) {
            // Bluetooth may have been switched off meanwhile; nothing left to stop.
            Log.w(TAG, "stopScan failed", e)
        }
    }
}
