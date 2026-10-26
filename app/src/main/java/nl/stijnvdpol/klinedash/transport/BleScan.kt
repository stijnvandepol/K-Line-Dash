package nl.stijnvdpol.klinedash.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "KLineDash"

/** [scanBle] refused to start for [block]; the screens turn this into their own words. */
internal class ScanBlockedException(val block: ScanBlock) : IllegalStateException("BLE scan blocked: $block")

/** The report of the latest scan in this process, for the Bluetooth block on Debug. Null until one ran. */
internal object BleScanLog {
    private val _last = MutableStateFlow<ScanReport?>(null)
    val last: StateFlow<ScanReport?> = _last.asStateFlow()

    fun record(report: ScanReport) {
        _last.value = report
    }
}

/** Scan starts of this process: Android's budget is per app, not per screen. */
private val scanThrottle = ScanThrottle()

/** Reads what a scan depends on. Also used by Debug, which is why the adapter is optional to pass in. */
internal fun scanEnvironment(context: Context, adapter: BluetoothAdapter? = bluetoothAdapter(context)): ScanEnvironment {
    val isOn = adapter?.isEnabled == true
    val locationManager = context.getSystemService(LocationManager::class.java)
    return ScanEnvironment(
        sdkInt = Build.VERSION.SDK_INT,
        androidRelease = Build.VERSION.RELEASE,
        hasBleFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
        hasAdapter = adapter != null,
        isBluetoothOn = isOn,
        hasLeScanner = isOn && adapter?.bluetoothLeScanner != null,
        isLocationOn = locationManager?.let { LocationManagerCompat.isLocationEnabled(it) },
        hasLocationPermission = isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION),
        hasNearbyPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isGranted(context, Manifest.permission.BLUETOOTH_SCAN) && isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            null
        },
    )
}

private fun isGranted(context: Context, permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/**
 * Looks for Bluetooth LE adapters for [durationMs]: an LE scan and, next to it, classic discovery,
 * because on some head-unit stacks the LE scan alone finds nothing while discovery also reports LE
 * devices (as [BluetoothDevice.DEVICE_TYPE_LE] or DEVICE_TYPE_DUAL). Both end when the time is up or
 * the collector's coroutine is cancelled; discovery is cancelled then too, as it slows other Bluetooth traffic.
 *
 * [onDevice] gets each device once, and again when a name turns up for a device listed without one.
 * Returns what the scan found and how it went; the same report goes to [BleScanLog].
 *
 * Throws [ScanBlockedException] when the scan should not start (see [decideScanBlock]), and
 * [SecurityException] when the permissions are missing. Needs BLUETOOTH_SCAN on API 31+ (declared with
 * `neverForLocation`) and ACCESS_FINE_LOCATION on API 26-30, where the system Location switch must also
 * be on or the stack silently drops every result.
 */
@SuppressLint("MissingPermission")
internal suspend fun scanBle(
    context: Context,
    durationMs: Long = BLE_SCAN_DURATION_MS,
    onDevice: (BtDeviceInfo) -> Unit,
): ScanReport {
    if (!hasRequiredPermissions(context)) {
        throw SecurityException("Permission to scan for Bluetooth devices is missing. Allow it in the Android settings.")
    }
    val appContext = context.applicationContext
    val adapter = bluetoothAdapter(appContext)
    val block = decideScanBlock(scanEnvironment(appContext, adapter), scanThrottle.waitMs(SystemClock.elapsedRealtime()))
    if (block != null) {
        BleScanLog.record(ScanReport(block = block))
        throw ScanBlockedException(block)
    }
    // decideScanBlock lets a scan through only when the adapter is on and has a scanner.
    val session = ScanSession(appContext, checkNotNull(adapter), checkNotNull(adapter.bluetoothLeScanner), onDevice)
    try {
        session.start()
        scanThrottle.recordStart(SystemClock.elapsedRealtime())
        withTimeoutOrNull(durationMs) { session.isOver.await() }
    } finally {
        session.stop()
        BleScanLog.record(session.report())
    }
    return session.report()
}

/**
 * One running scan: the LE callback and the discovery receiver, both delivered on the main thread, so
 * the tally needs no locking. Whatever [start] did is undone by [stop], also when [start] failed halfway.
 */
@SuppressLint("MissingPermission")
private class ScanSession(
    context: Context,
    private val adapter: BluetoothAdapter,
    private val scanner: BluetoothLeScanner,
    private val onDevice: (BtDeviceInfo) -> Unit,
) {
    private val tally = ScanTally()
    private var failureCode: Int? = null
    private var isDiscoveryStarted = false
    private var isLeScanStarted = false
    private val discovery = DiscoverySession(context, adapter) { device, rawName ->
        if (tally.onDiscoverySighting(device.address, !rawName.isNullOrBlank(), device.typeOrUnknown())) {
            onDevice(btDeviceInfo(rawName, device.address))
        }
    }

    /** Completed early when the LE scan failed and discovery is not running either: nothing is left to wait for. */
    val isOver = CompletableDeferred<Unit>()

    private val leCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handle(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
            failureCode = errorCode
            if (!isDiscoveryStarted) isOver.complete(Unit)
        }
    }

    fun start() {
        isDiscoveryStarted = discovery.start()
        // Not MATCH_MODE_AGGRESSIVE, MAX_ADVERTISEMENT or setLegacy(false): the first two only act on scans
        // with filters, and a legacy (Bluetooth 4.x) adapter is what we look for; extended advertising
        // support varies per controller and can make the scan fail on older stacks.
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), leCallback)
        isLeScanStarted = true
    }

    fun stop() {
        if (isLeScanStarted) {
            try {
                scanner.stopScan(leCallback)
            } catch (e: RuntimeException) {
                // Bluetooth may have been switched off meanwhile; nothing left to stop.
                Log.w(TAG, "stopScan failed", e)
            }
        }
        discovery.stop()
    }

    fun report(): ScanReport = tally.report(isDiscoveryStarted, failureCode)

    private fun handle(result: ScanResult) {
        val device = result.device ?: return
        val rawName = result.scanRecord?.deviceName ?: device.nameOrNull()
        if (tally.onLeSighting(device.address, !rawName.isNullOrBlank())) onDevice(btDeviceInfo(rawName, device.address))
    }
}

/**
 * Classic discovery (an inquiry of about 12 s) with its broadcast receiver: [onFound] gets every device
 * the system reports, with its name if known, also again when a name arrives later. [onFinished] runs
 * when the stack ends the inquiry by itself. Used by the LE scan, which lets discovery run beside it,
 * and by the Bluetooth Classic search. Delivered on the main thread. [stop] undoes whatever [start] did,
 * also when [start] failed halfway.
 */
@SuppressLint("MissingPermission")
internal class DiscoverySession(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val onFinished: () -> Unit = {},
    private val onFound: (device: BluetoothDevice, rawName: String?) -> Unit,
) {
    private var isReceiverRegistered = false
    private var isStarted = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                onFinished()
                return
            }
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            onFound(device, intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: device.nameOrNull())
        }
    }

    /** False when the stack refuses discovery (Bluetooth just went off, or another discovery runs). */
    fun start(): Boolean {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // RECEIVER_EXPORTED: these are protected system broadcasts, so only the system can send them.
        // RECEIVER_NOT_EXPORTED would add a signature permission that the Bluetooth process does not hold.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        isReceiverRegistered = true
        isStarted = try {
            adapter.startDiscovery()
        } catch (e: SecurityException) {
            Log.w(TAG, "startDiscovery refused", e)
            false
        }
        return isStarted
    }

    fun stop() {
        if (isReceiverRegistered) context.unregisterReceiver(receiver)
        // Only a discovery we started: one that was already running belongs to someone else.
        if (isStarted) {
            try {
                adapter.cancelDiscovery()
            } catch (e: RuntimeException) {
                Log.w(TAG, "cancelDiscovery failed", e)
            }
        }
    }
}
