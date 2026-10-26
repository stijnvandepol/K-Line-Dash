package nl.stijnvdpol.klinedash.transport

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Looks for Bluetooth Classic adapters, for [durationMs] or until the system
 * ends its inquiry, whichever comes first (classic discovery, the same receiver and permissions as
 * [scanBle]). LE-only devices are not reported; every device class is, because adapters such as an
 * ESP32 bridge present themselves as a computer. Devices that are already paired are reported too,
 * flagged [BtDeviceInfo.isPaired]: a stale pairing must not hide the adapter. [onDevice] gets each
 * device once, and again when its name turns up later.
 *
 * Returns whether the system started the discovery. Throws [ScanBlockedException] when the search
 * should not start (see [decideScanBlock]) and [SecurityException] when the permissions are missing.
 * Discovery is cancelled when this returns or its coroutine is cancelled: it slows other Bluetooth
 * traffic, and bonding fails reliably while it runs.
 */
@SuppressLint("MissingPermission")
internal suspend fun searchClassic(
    context: Context,
    durationMs: Long = BLE_SCAN_DURATION_MS,
    onDevice: (BtDeviceInfo) -> Unit,
): Boolean {
    if (!hasRequiredPermissions(context)) {
        throw SecurityException("Permission to search for Bluetooth devices is missing. Allow it in the Android settings.")
    }
    val appContext = context.applicationContext
    val adapter = bluetoothAdapter(appContext)
    val block = decideScanBlock(scanEnvironment(appContext, adapter), throttleWaitMs = 0, needsLeScanner = false)
    if (block != null) throw ScanBlockedException(block)
    val isOver = CompletableDeferred<Unit>()
    // decideScanBlock lets a search through only when the adapter exists and is on.
    val discovery = DiscoverySession(appContext, checkNotNull(adapter), onFinished = { isOver.complete(Unit) }) { device, rawName ->
        if (isClassicCapable(device.typeOrUnknown())) {
            onDevice(btDeviceInfo(rawName, device.address, isPaired = device.bondStateOrNone() == BondState.BONDED))
        }
    }
    try {
        val isStarted = discovery.start()
        if (isStarted) withTimeoutOrNull(durationMs) { isOver.await() }
        return isStarted
    } finally {
        discovery.stop()
    }
}
