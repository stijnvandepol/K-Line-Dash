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
import nl.stijnvdpol.klinedash.core.transport.TransportKind

private const val TAG = "KLineDash"

/**
 * A Bluetooth device the user can pick as OBD adapter.
 *
 * @property name display name; [UNNAMED_DEVICE] when the device does not advertise one
 * @property looksLikeObd name matches a known ELM327/OBD adapter brand (heuristic only)
 * @property knownAppLocked name matches a dongle that only works with its own app (BlueDriver,
 *   ThinkDiag, ...). A warning for the list, never a reason to refuse: a device that answers is used.
 */
data class BtDeviceInfo(
    val name: String,
    val address: String,
    val looksLikeObd: Boolean,
    val knownAppLocked: Boolean = false,
)

private const val UNNAMED_DEVICE = "(unnamed)"

private val OBD_NAME_HINTS = listOf(
    "OBD", "ELM", "V-LINK", "VLINK", "VGATE", "ICAR", "KONNWEI", "KW905", "VIECAR",
    "VEEPEAK", "OBDLINK", "CAN BUS", "BLUEDRIVER", "LELINK", "KIWI", "V-CHECKER", "TONWON", "HGC", "MAXIOBD",
)

/**
 * Advertised names of dongles that only talk to their vendor's app (docs/research/popular-obd-adapters.md,
 * section 1b). FIXD and Carista are left out on purpose: unverified, not known to be locked.
 */
private val APP_LOCKED_NAME_HINTS = listOf(
    "BLUEDRIVER", "TOPDON", "TOPSCAN", "THINKDIAG", "THINKCAR", "THINKTOOL", "X431", "EASYDIAG",
    "GOLO", "M-DIAG", "ANCEL", "AUTEL", "MAXIAP", "FASTLINK", "CARLY", "OBDELEVEN",
)

internal fun nameLooksLikeObd(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    val upper = name.uppercase()
    return OBD_NAME_HINTS.any { upper.contains(it) }
}

internal fun isKnownAppLocked(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    val upper = name.uppercase()
    return APP_LOCKED_NAME_HINTS.any { upper.contains(it) }
}

/** Whether connecting over this transport needs the Bluetooth runtime permissions below. */
fun TransportKind.needsBluetoothPermissions(): Boolean = this == TransportKind.BT_CLASSIC || this == TransportKind.BLE

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
            "Bluetooth permission is missing (BLUETOOTH_CONNECT). Allow it under " +
                "Settings > Apps > K-Line Dash > Permissions.",
        )
    }
}

/**
 * The system adapter, or null. Head units with a proprietary Bluetooth module (phone-call only
 * stacks) often return null here or an adapter that cannot open RFCOMM sockets for third-party apps.
 */
internal fun bluetoothAdapter(context: Context): BluetoothAdapter? =
    context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter

internal const val ADAPTER_UNAVAILABLE_MESSAGE = "Bluetooth is not available to apps on this head unit. Use a Wi-Fi adapter instead."
internal const val BLUETOOTH_OFF_MESSAGE = "Bluetooth is off. Turn Bluetooth on and try again."

/** Shown in a transport description until the device's own name is known. */
internal const val DEFAULT_ADAPTER_NAME = "OBD-adapter"

internal fun invalidAddressMessage(address: String) = "Invalid Bluetooth address: $address"

/**
 * Shown when every Bluetooth Classic connect strategy failed. The pairing hint only appears for an
 * adapter that is not paired yet; buttons are common on dual-mode adapters that sleep or hide
 * until pressed (Konnwei KW905, vLinker MC+, OBDLink).
 */
internal fun classicConnectFailedMessage(isBonded: Boolean): String = buildString {
    append("Cannot connect to the adapter over Bluetooth. Check that it is plugged into the OBD port and the ignition is on. ")
    if (!isBonded) append("Pair the adapter first (PIN 1234, 0000 or 6789). ")
    append("Press the adapter's button if it has one.")
}

/**
 * The device's advertised name, or null. The permission can be revoked at any moment; the name is
 * cosmetic, so then the device is just shown unnamed (a connect attempt fails loudly instead).
 */
@SuppressLint("MissingPermission")
internal fun BluetoothDevice.nameOrNull(): String? = try {
    name
} catch (e: SecurityException) {
    null
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
                val rawName = d.nameOrNull()
                BtDeviceInfo(
                    name = rawName?.takeIf { it.isNotBlank() } ?: UNNAMED_DEVICE,
                    address = d.address,
                    looksLikeObd = nameLooksLikeObd(rawName),
                    knownAppLocked = isKnownAppLocked(rawName),
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
 * (English message) when Bluetooth is off/unavailable or the scan fails to start, so collect it
 * inside try/catch or with `.catch {}`.
 *
 * Needs BLUETOOTH_SCAN on API 31+ (declare it with `neverForLocation` in the manifest) and
 * ACCESS_FINE_LOCATION on API 26-30. On 26-30 the system "Location" toggle must also be on, or the
 * scan silently returns nothing; head units often have it off.
 */
@SuppressLint("MissingPermission")
fun scanBle(context: Context, durationMs: Long = 8000): Flow<BtDeviceInfo> = callbackFlow<BtDeviceInfo> {
    if (!hasRequiredPermissions(context)) {
        close(SecurityException("Permission to scan for Bluetooth devices is missing. Allow it in the Android settings."))
        awaitClose { }
        return@callbackFlow
    }
    val adapter = bluetoothAdapter(context)
    val scanner = if (adapter != null && adapter.isEnabled) adapter.bluetoothLeScanner else null
    if (scanner == null) {
        close(IllegalStateException(
            if (adapter == null) ADAPTER_UNAVAILABLE_MESSAGE else BLUETOOTH_OFF_MESSAGE,
        ))
        awaitClose { }
        return@callbackFlow
    }

    val seen = HashSet<String>()

    fun handle(result: ScanResult) {
        val device = result.device ?: return
        if (!seen.add(device.address)) return
        val rawName = result.scanRecord?.deviceName ?: device.nameOrNull()
        trySend(
            BtDeviceInfo(
                name = rawName?.takeIf { it.isNotBlank() } ?: UNNAMED_DEVICE,
                address = device.address,
                looksLikeObd = nameLooksLikeObd(rawName),
                knownAppLocked = isKnownAppLocked(rawName),
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
            close(IllegalStateException("BLE scan failed (error code $errorCode)"))
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
