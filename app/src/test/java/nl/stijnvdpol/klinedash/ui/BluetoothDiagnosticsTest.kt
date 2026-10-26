package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.transport.ScanBlock
import nl.stijnvdpol.klinedash.transport.ScanEnvironment
import nl.stijnvdpol.klinedash.transport.ScanReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothDiagnosticsTest {
    private val words = BluetoothWords(
        yes = "Yes", no = "No", on = "On", off = "Off", granted = "Granted", denied = "Denied", none = "None",
        noAdapter = "No adapter", onWithoutScanner = "On, no LE scanner", notStarted = "Not started",
        android = { release, api -> "$release (API $api)" },
        devicesFound = { count -> "$count" },
        discoveryFound = { count, classicOnly -> "$count (+$classicOnly Classic)" },
    )

    private val environment = ScanEnvironment(
        sdkInt = 30, androidRelease = "11", hasBleFeature = true, hasAdapter = true, isBluetoothOn = true,
        hasLeScanner = true, isLocationOn = false, hasLocationPermission = true, hasNearbyPermission = null,
    )

    private fun values(scan: ScanReport?, env: ScanEnvironment = environment) =
        bluetoothValues(BluetoothDiagnostics(env, scan), words)

    @Test
    fun `the device rows say which case an empty scan is`() {
        val rows = values(scan = null)
        assertEquals("11 (API 30)", rows[BluetoothField.ANDROID])
        assertEquals("Yes", rows[BluetoothField.LE_SUPPORTED])
        assertEquals("On", rows[BluetoothField.BLUETOOTH])
        assertEquals("Off", rows[BluetoothField.LOCATION_SWITCH])
        assertEquals("Granted", rows[BluetoothField.LOCATION_PERMISSION])
        assertNull(rows[BluetoothField.NEARBY_PERMISSION])
    }

    @Test
    fun `before the first scan the scan rows are empty`() {
        val rows = values(scan = null)
        listOf(BluetoothField.SCAN_LE, BluetoothField.SCAN_DISCOVERY, BluetoothField.SCAN_FAILURE, BluetoothField.SCAN_THROTTLED)
            .forEach { assertNull(it.name, rows[it]) }
    }

    @Test
    fun `the rows come in the order of the enum`() {
        assertEquals(BluetoothField.entries.toList(), values(null).keys.toList())
    }

    @Test
    fun `bluetooth state tells off, no adapter and no scanner apart`() {
        assertEquals("Off", values(null, environment.copy(isBluetoothOn = false))[BluetoothField.BLUETOOTH])
        assertEquals("No adapter", values(null, environment.copy(hasAdapter = false))[BluetoothField.BLUETOOTH])
        assertEquals("On, no LE scanner", values(null, environment.copy(hasLeScanner = false))[BluetoothField.BLUETOOTH])
    }

    @Test
    fun `on Android 12 the nearby devices permission shows and an unknown location switch is empty`() {
        val rows = values(null, environment.copy(sdkInt = 33, androidRelease = "13", hasNearbyPermission = false, isLocationOn = null))
        assertEquals("Denied", rows[BluetoothField.NEARBY_PERMISSION])
        assertNull(rows[BluetoothField.LOCATION_SWITCH])
    }

    @Test
    fun `a finished scan shows both counts, the failure code and throttling`() {
        val scan = ScanReport(leDevices = 0, discoveryDevices = 2, classicOnlyDevices = 3, isDiscoveryStarted = true, failureCode = 2)
        val rows = values(scan)
        assertEquals("0", rows[BluetoothField.SCAN_LE])
        assertEquals("2 (+3 Classic)", rows[BluetoothField.SCAN_DISCOVERY])
        assertEquals("2", rows[BluetoothField.SCAN_FAILURE])
        assertEquals("No", rows[BluetoothField.SCAN_THROTTLED])
    }

    @Test
    fun `a scan without failure says none and a discovery that never started says so`() {
        val rows = values(ScanReport(leDevices = 1))
        assertEquals("None", rows[BluetoothField.SCAN_FAILURE])
        assertEquals("Not started", rows[BluetoothField.SCAN_DISCOVERY])
    }

    @Test
    fun `a blocked scan shows not started and throttled when the budget said wait`() {
        val blocked = values(ScanReport(block = ScanBlock.Throttled(9)))
        assertEquals("Not started", blocked[BluetoothField.SCAN_LE])
        assertEquals("Not started", blocked[BluetoothField.SCAN_DISCOVERY])
        assertEquals("Yes", blocked[BluetoothField.SCAN_THROTTLED])
        assertEquals("No", values(ScanReport(block = ScanBlock.LocationOff))[BluetoothField.SCAN_THROTTLED])
    }
}
