package nl.stijnvdpol.klinedash.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
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
 * The device type (DEVICE_TYPE_LE, ...), or DEVICE_TYPE_UNKNOWN when the permission is gone; like
 * [nameOrNull], it only decides what is listed, a connect attempt fails loudly instead.
 */
@SuppressLint("MissingPermission")
internal fun BluetoothDevice.typeOrUnknown(): Int = try {
    type
} catch (e: SecurityException) {
    BluetoothDevice.DEVICE_TYPE_UNKNOWN
}

/** The list entry for a device with its advertised [rawName] (null or blank: shown as unnamed). */
internal fun btDeviceInfo(rawName: String?, address: String) = BtDeviceInfo(
    name = rawName?.takeIf { it.isNotBlank() } ?: UNNAMED_DEVICE,
    address = address,
    looksLikeObd = nameLooksLikeObd(rawName),
    knownAppLocked = isKnownAppLocked(rawName),
)

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
            .map { d -> btDeviceInfo(d.nameOrNull(), d.address) }
            .sortedWith(compareByDescending<BtDeviceInfo> { it.looksLikeObd }.thenBy { it.name.lowercase() })
    } catch (e: SecurityException) {
        Log.w(TAG, "bondedDevices: permission revoked", e)
        emptyList()
    }
}
