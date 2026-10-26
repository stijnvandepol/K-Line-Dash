package nl.stijnvdpol.klinedash.transport

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleScanPolicyTest {
    private val ready = ScanEnvironment(
        sdkInt = 30,
        androidRelease = "11",
        hasBleFeature = true,
        hasAdapter = true,
        isBluetoothOn = true,
        hasLeScanner = true,
        isLocationOn = true,
        hasLocationPermission = true,
        hasNearbyPermission = null,
    )

    @Test
    fun `a ready device may scan`() {
        assertNull(decideScanBlock(ready, throttleWaitMs = 0))
    }

    @Test
    fun `location off blocks the scan on Android 11 and older`() {
        assertEquals(ScanBlock.LocationOff, decideScanBlock(ready.copy(isLocationOn = false), 0))
        assertEquals(ScanBlock.LocationOff, decideScanBlock(ready.copy(sdkInt = 26, isLocationOn = false), 0))
    }

    @Test
    fun `location off does not block from Android 12`() {
        assertNull(decideScanBlock(ready.copy(sdkInt = 31, isLocationOn = false), 0))
    }

    @Test
    fun `a device without a location service is not blocked on location`() {
        assertNull(decideScanBlock(ready.copy(isLocationOn = null), 0))
    }

    @Test
    fun `no adapter, bluetooth off and no scanner each have their own block`() {
        assertEquals(ScanBlock.NoAdapter, decideScanBlock(ready.copy(hasAdapter = false, isBluetoothOn = false, hasLeScanner = false), 0))
        assertEquals(ScanBlock.BluetoothOff, decideScanBlock(ready.copy(isBluetoothOn = false, hasLeScanner = false), 0))
        assertEquals(ScanBlock.NoLeScanner, decideScanBlock(ready.copy(hasLeScanner = false), 0))
    }

    @Test
    fun `a missing LE declaration does not block when the scanner exists`() {
        assertNull(decideScanBlock(ready.copy(hasBleFeature = false), 0))
    }

    @Test
    fun `a declared LE feature does not help without a scanner`() {
        assertEquals(ScanBlock.NoLeScanner, decideScanBlock(ready.copy(hasBleFeature = true, hasLeScanner = false), 0))
    }

    @Test
    fun `throttling reports whole seconds rounded up`() {
        assertEquals(ScanBlock.Throttled(1), decideScanBlock(ready, 1))
        assertEquals(ScanBlock.Throttled(1), decideScanBlock(ready, 1_000))
        assertEquals(ScanBlock.Throttled(13), decideScanBlock(ready, 12_001))
    }

    @Test
    fun `a problem the user must fix is reported before throttling`() {
        assertEquals(ScanBlock.LocationOff, decideScanBlock(ready.copy(isLocationOn = false), 5_000))
    }

    @Test
    fun `the budget allows five starts and makes the sixth wait for the oldest to expire`() {
        val throttle = ScanThrottle(maxStarts = 5, windowMs = 30_000)
        for (second in 0..4) {
            assertEquals(0L, throttle.waitMs(second * 1_000L))
            throttle.recordStart(second * 1_000L)
        }
        assertEquals(26_000L, throttle.waitMs(4_000))
        assertEquals(1L, throttle.waitMs(29_999))
        assertEquals(0L, throttle.waitMs(30_000))
    }

    @Test
    fun `starts older than the window no longer count`() {
        val throttle = ScanThrottle(maxStarts = 2, windowMs = 10_000)
        throttle.recordStart(0)
        throttle.recordStart(1_000)
        assertEquals(9_000L, throttle.waitMs(1_000))
        assertEquals(0L, throttle.waitMs(10_000))
        throttle.recordStart(10_000)
        assertEquals(1_000L, throttle.waitMs(10_000))
    }

    @Test
    fun `asking does not use up the budget`() {
        val throttle = ScanThrottle(maxStarts = 1, windowMs = 10_000)
        assertEquals(0L, throttle.waitMs(0))
        assertEquals(0L, throttle.waitMs(1))
    }

    @Test
    fun `the default budget is five scans in a little over thirty seconds`() {
        val throttle = ScanThrottle()
        repeat(MAX_SCAN_STARTS) { throttle.recordStart(0) }
        assertEquals(SCAN_BUDGET_WINDOW_MS, throttle.waitMs(0))
        assertTrue(SCAN_BUDGET_WINDOW_MS > 30_000)
    }

    @Test
    fun `a device is listed once, and again when its name arrives`() {
        val tally = ScanTally()
        assertTrue(tally.onLeSighting("AA", hasName = false))
        assertFalse(tally.onLeSighting("AA", hasName = false))
        assertTrue(tally.onLeSighting("AA", hasName = true))
        assertFalse(tally.onLeSighting("AA", hasName = true))
        assertFalse(tally.onLeSighting("AA", hasName = false))
    }

    @Test
    fun `a device named from the first sighting is listed once`() {
        val tally = ScanTally()
        assertTrue(tally.onLeSighting("AA", hasName = true))
        assertFalse(tally.onLeSighting("AA", hasName = true))
    }

    @Test
    fun `discovery adds LE and dual devices and merges with the LE scan`() {
        val tally = ScanTally()
        assertTrue(tally.onLeSighting("AA", hasName = true))
        assertFalse(tally.onDiscoverySighting("AA", hasName = true, deviceType = BluetoothDevice.DEVICE_TYPE_LE))
        assertTrue(tally.onDiscoverySighting("BB", hasName = true, deviceType = BluetoothDevice.DEVICE_TYPE_DUAL))
        assertTrue(tally.onDiscoverySighting("CC", hasName = false, deviceType = BluetoothDevice.DEVICE_TYPE_LE))
        val report = tally.report(isDiscoveryStarted = true, failureCode = null)
        assertEquals(1, report.leDevices)
        assertEquals(3, report.discoveryDevices)
    }

    @Test
    fun `discovery finding the adapter the LE scan missed lists it`() {
        val tally = ScanTally()
        assertTrue(tally.onDiscoverySighting("KW905", hasName = true, deviceType = BluetoothDevice.DEVICE_TYPE_LE))
        assertEquals(0, tally.report(true, null).leDevices)
    }

    @Test
    fun `classic only and unknown devices are counted but never listed`() {
        val tally = ScanTally()
        assertFalse(tally.onDiscoverySighting("AA", hasName = true, deviceType = BluetoothDevice.DEVICE_TYPE_CLASSIC))
        assertFalse(tally.onDiscoverySighting("BB", hasName = true, deviceType = BluetoothDevice.DEVICE_TYPE_UNKNOWN))
        assertFalse(tally.onDiscoverySighting("AA", hasName = true, deviceType = BluetoothDevice.DEVICE_TYPE_CLASSIC))
        val report = tally.report(true, null)
        assertEquals(0, report.discoveryDevices)
        assertEquals(2, report.classicOnlyDevices)
    }

    @Test
    fun `the report carries the failure code and the discovery state`() {
        val report = ScanTally().report(isDiscoveryStarted = false, failureCode = ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)
        assertEquals(2, report.failureCode)
        assertFalse(report.isDiscoveryStarted)
        assertFalse(report.isThrottled)
    }

    @Test
    fun `a scan is throttled by our budget or by the refusal of the stack`() {
        assertTrue(ScanReport(block = ScanBlock.Throttled(5)).isThrottled)
        assertTrue(ScanReport(failureCode = SCAN_FAILED_TOO_FREQUENTLY).isThrottled)
        assertFalse(ScanReport(block = ScanBlock.LocationOff).isThrottled)
    }

    @Test
    fun `a named sighting replaces the unnamed entry in place and likely OBD adapters come first`() {
        val plain = BtDeviceInfo("(unnamed)", "AA", looksLikeObd = false)
        val other = BtDeviceInfo("Speaker", "BB", looksLikeObd = false)
        val named = BtDeviceInfo("KONNWEI", "AA", looksLikeObd = true)
        val merged = mergeScanResult(mergeScanResult(emptyList(), plain), other)
        assertEquals(listOf(plain, other), merged)
        assertEquals(listOf(named, other), mergeScanResult(merged, named))
    }

    @Test
    fun `the checklist leaves out Location from Android 12`() {
        assertTrue(ScanCheck.LOCATION_ON in emptyScanChecks(30))
        assertFalse(ScanCheck.LOCATION_ON in emptyScanChecks(31))
        assertEquals(4, emptyScanChecks(34).size)
        assertEquals(5, emptyScanChecks(26).size)
    }

    @Test
    fun `scans last long enough for a slow advertiser and a classic inquiry`() {
        assertTrue(BLE_SCAN_DURATION_MS >= 12_000)
    }

    @Test
    fun `a discovery finish before our own start is a late one and is ignored`() {
        val progress = DiscoveryProgress()
        assertFalse(progress.isOurFinish())
        progress.onStarted()
        assertTrue(progress.isOurFinish())
    }
}
