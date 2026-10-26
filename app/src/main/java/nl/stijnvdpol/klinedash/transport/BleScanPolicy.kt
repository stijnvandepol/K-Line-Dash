package nl.stijnvdpol.klinedash.transport

import android.bluetooth.BluetoothDevice

/*
 * The rules around a Bluetooth LE scan, free of Android classes so they can be tested: whether a scan
 * may start (and if not, why), how often Android lets an app scan, how the results of the LE scan and
 * of classic discovery merge, and what the screens say when nothing was found. BleScan.kt is the glue.
 */

/** Longest SDK level on which a BLE scan needs ACCESS_FINE_LOCATION and the system Location switch (Android 11). */
private const val LAST_SDK_NEEDING_LOCATION = 30

/**
 * One scan covers an LE scan and classic discovery together. Discovery "usually involves an inquiry
 * scan of about 12 seconds" (BluetoothAdapter.startDiscovery), and an LE advertiser may legally wait up
 * to 10.24 s between packets (Bluetooth Core spec), so a shorter scan can miss a slow adapter.
 */
internal const val BLE_SCAN_DURATION_MS = 12_000L

/**
 * Android lets an app start 5 scans within 30 seconds (AOSP AppScanStats: a quota of 5 scans, each
 * remembered by its start time, over a 30 s window). The 6th start is not refused: the stack silently
 * delivers nothing. One second of margin covers the difference between Android's clock reading and ours.
 */
internal const val MAX_SCAN_STARTS = 5
internal const val SCAN_BUDGET_WINDOW_MS = 31_000L

private const val MS_PER_SECOND = 1_000L

/** Whether a BLE scan needs the Location permission and the Location switch on this Android version. */
internal fun needsLocationForScan(sdkInt: Int): Boolean = sdkInt <= LAST_SDK_NEEDING_LOCATION

/** Why a scan was not started. [Throttled] clears by itself; the others need the user. */
internal sealed interface ScanBlock {
    data object NoAdapter : ScanBlock
    data object BluetoothOff : ScanBlock
    data object NoLeScanner : ScanBlock
    data object LocationOff : ScanBlock
    data class Throttled(val waitSeconds: Int) : ScanBlock
}

/**
 * What the device says about itself, as far as a scan depends on it. The same snapshot feeds
 * [decideScanBlock] and the Bluetooth block on Debug.
 *
 * @property hasBleFeature the system declares FEATURE_BLUETOOTH_LE. Informational: some head units
 *   leave the declaration out and scan fine, so a scanner that exists wins over a missing declaration.
 * @property isLocationOn the system Location switch; null when the device has no location service to ask
 * @property hasNearbyPermission BLUETOOTH_SCAN and BLUETOOTH_CONNECT; null below Android 12, where they do not exist
 */
internal data class ScanEnvironment(
    val sdkInt: Int,
    val androidRelease: String,
    val hasBleFeature: Boolean,
    val hasAdapter: Boolean,
    val isBluetoothOn: Boolean,
    val hasLeScanner: Boolean,
    val isLocationOn: Boolean?,
    val hasLocationPermission: Boolean,
    val hasNearbyPermission: Boolean?,
)

/**
 * The reason not to start a scan, or null when it may start. Location is only blocking when the
 * system says it is off: a device with no location service gets the scan, as it would be refused by
 * the Bluetooth stack itself rather than by this check. Classic discovery has no LE scanner to need
 * ([needsLeScanner] false) and no scan budget (it passes 0 as [throttleWaitMs]).
 */
internal fun decideScanBlock(
    environment: ScanEnvironment,
    throttleWaitMs: Long,
    needsLeScanner: Boolean = true,
): ScanBlock? = when {
    !environment.hasAdapter -> ScanBlock.NoAdapter
    !environment.isBluetoothOn -> ScanBlock.BluetoothOff
    needsLeScanner && !environment.hasLeScanner -> ScanBlock.NoLeScanner
    needsLocationForScan(environment.sdkInt) && environment.isLocationOn == false -> ScanBlock.LocationOff
    throttleWaitMs > 0 -> ScanBlock.Throttled(secondsToWait(throttleWaitMs))
    else -> null
}

private fun secondsToWait(waitMs: Long): Int = ((waitMs + MS_PER_SECOND - 1) / MS_PER_SECOND).toInt()

/**
 * Remembers when scans started, to stay inside Android's budget. Process-wide in use (the budget is
 * per app, not per screen); the time is passed in so tests need no clock.
 */
internal class ScanThrottle(
    private val maxStarts: Int = MAX_SCAN_STARTS,
    private val windowMs: Long = SCAN_BUDGET_WINDOW_MS,
) {
    private val starts = ArrayDeque<Long>()

    /** Milliseconds until a scan may start; 0 when it may start now. */
    @Synchronized
    fun waitMs(nowMs: Long): Long {
        forgetExpired(nowMs)
        return if (starts.size < maxStarts) 0L else starts.first() + windowMs - nowMs
    }

    @Synchronized
    fun recordStart(nowMs: Long) {
        forgetExpired(nowMs)
        starts.addLast(nowMs)
    }

    private fun forgetExpired(nowMs: Long) {
        while (starts.isNotEmpty() && starts.first() + windowMs <= nowMs) starts.removeFirst()
    }
}

/**
 * ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY. The SDK declares the constant since API 33; the number
 * is the one the Bluetooth stack reports, which older versions may use as well.
 */
internal const val SCAN_FAILED_TOO_FREQUENTLY = 6

/** What one scan found and how it went, for Debug. [block] is set when the scan never started. */
internal data class ScanReport(
    val leDevices: Int = 0,
    val discoveryDevices: Int = 0,
    val classicOnlyDevices: Int = 0,
    val isDiscoveryStarted: Boolean = false,
    val failureCode: Int? = null,
    val block: ScanBlock? = null,
) {
    /** Our own budget said wait, or the stack reported SCAN_FAILED_SCANNING_TOO_FREQUENTLY (6). */
    val isThrottled: Boolean get() = block is ScanBlock.Throttled || failureCode == SCAN_FAILED_TOO_FREQUENTLY
}

/** A device a GATT connection can reach: LE-only or dual-mode. */
internal fun isLeCapable(deviceType: Int): Boolean =
    deviceType == BluetoothDevice.DEVICE_TYPE_LE || deviceType == BluetoothDevice.DEVICE_TYPE_DUAL

/**
 * Merges what the LE scan and classic discovery report: each device is listed once, and listed again
 * only when a name turns up for a device that was listed without one (an LE advertisement often
 * carries the name in a later packet). Discovery also reports Classic-only devices; those are
 * counted but never listed, because the LE transport cannot use them.
 */
internal class ScanTally {
    private val leSeen = HashSet<String>()
    private val discoverySeen = HashSet<String>()
    private val classicOnlySeen = HashSet<String>()
    private val listed = HashSet<String>()
    private val listedWithName = HashSet<String>()

    /** True when the list must show (or update) this device. */
    fun onLeSighting(address: String, hasName: Boolean): Boolean {
        leSeen.add(address)
        return mustList(address, hasName)
    }

    fun onDiscoverySighting(address: String, hasName: Boolean, deviceType: Int): Boolean {
        if (!isLeCapable(deviceType)) {
            classicOnlySeen.add(address)
            return false
        }
        discoverySeen.add(address)
        return mustList(address, hasName)
    }

    fun report(isDiscoveryStarted: Boolean, failureCode: Int?) = ScanReport(
        leDevices = leSeen.size,
        discoveryDevices = discoverySeen.size,
        classicOnlyDevices = classicOnlySeen.size,
        isDiscoveryStarted = isDiscoveryStarted,
        failureCode = failureCode,
    )

    private fun mustList(address: String, hasName: Boolean): Boolean {
        val isNew = listed.add(address)
        val isNameNew = hasName && listedWithName.add(address)
        return isNew || isNameNew
    }
}

/**
 * Tells a DISCOVERY_FINISHED that ends our discovery from a late one of an earlier discovery (the
 * cancel of the previous search): only a finish after our own DISCOVERY_STARTED counts.
 */
internal class DiscoveryProgress {
    private var isStarted = false

    fun onStarted() {
        isStarted = true
    }

    /** True when the finish belongs to our discovery. */
    fun isOurFinish(): Boolean = isStarted
}

/** The list with [device] added, or replacing the entry of the same address; likely OBD adapters first. */
internal fun mergeScanResult(list: List<BtDeviceInfo>, device: BtDeviceInfo): List<BtDeviceInfo> {
    val index = list.indexOfFirst { it.address == device.address }
    val merged = if (index < 0) list + device else list.toMutableList().also { it[index] = device }
    return merged.sortedByDescending { it.looksLikeObd }
}

/** What the device list area says besides the devices; null means nothing special. */
internal sealed interface BleScanNotice {
    data class Blocked(val block: ScanBlock) : BleScanNotice

    /** The scan ran to its end without a device; [failureCode] is the stack's error, if it gave one. */
    data class NothingFound(val failureCode: Int?) : BleScanNotice

    /** A Bluetooth Classic search ran to its end without a device. */
    data object NothingFoundClassic : BleScanNotice

    /** Android would not start the Bluetooth Classic search. */
    data object DiscoveryRefused : BleScanNotice

    /** The Bluetooth permission (Nearby devices, or Location on Android 11 and older) is not granted. */
    data object PermissionMissing : BleScanNotice
}

/** One line of the checklist shown when a scan found nothing. */
internal enum class ScanCheck { LOCATION_ON, ADAPTER_POWERED, NOT_CONNECTED_ELSEWHERE, WAIT_BETWEEN_SCANS, LOOK_IN_ANDROID_SETTINGS }

/** The checklist for [sdkInt]: the Location line only where Location matters. */
internal fun emptyScanChecks(sdkInt: Int): List<ScanCheck> = ScanCheck.entries.filter {
    it != ScanCheck.LOCATION_ON || needsLocationForScan(sdkInt)
}
