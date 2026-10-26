package nl.stijnvdpol.klinedash.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.transport.ScanEnvironment
import nl.stijnvdpol.klinedash.transport.ScanReport

/*
 * The Bluetooth block on Debug: what the device and the last scan say, in few enough rows for one
 * screenshot to show which case a "the scan finds nothing" report is.
 */

/** What the block shows: the device as it is now and the report of the last scan (null before the first). */
internal data class BluetoothDiagnostics(val environment: ScanEnvironment, val lastScan: ScanReport?)

/** Rows of the Bluetooth block, in display order. */
internal enum class BluetoothField(val labelRes: Int) {
    ANDROID(R.string.label_bt_android),
    LE_SUPPORTED(R.string.label_bt_le_supported),
    BLUETOOTH(R.string.label_bt_state),
    LOCATION_SWITCH(R.string.label_bt_location_switch),
    LOCATION_PERMISSION(R.string.label_bt_location_permission),
    NEARBY_PERMISSION(R.string.label_bt_nearby_permission),
    SCAN_LE(R.string.label_bt_scan_le),
    SCAN_DISCOVERY(R.string.label_bt_scan_discovery),
    SCAN_FAILURE(R.string.label_bt_scan_failure),
    SCAN_THROTTLED(R.string.label_bt_scan_throttled),
}

/** The words the block is written in; the formatted ones are functions so numbers arrive as text. */
internal data class BluetoothWords(
    val yes: String,
    val no: String,
    val on: String,
    val off: String,
    val granted: String,
    val denied: String,
    val none: String,
    val noAdapter: String,
    val onWithoutScanner: String,
    val notStarted: String,
    val android: (release: String, api: String) -> String,
    val devicesFound: (count: String) -> String,
    val discoveryFound: (count: String, classicOnly: String) -> String,
)

/**
 * What the block shows per field. A null value renders as "--": not applicable on this Android
 * version, unknown, or no scan has run yet.
 */
internal fun bluetoothValues(diagnostics: BluetoothDiagnostics, words: BluetoothWords): Map<BluetoothField, String?> {
    val environment = diagnostics.environment
    val scan = diagnostics.lastScan
    return BluetoothField.entries.associateWith { field ->
        when (field) {
            BluetoothField.ANDROID -> words.android(environment.androidRelease, environment.sdkInt.toString())
            BluetoothField.LE_SUPPORTED -> yesNo(environment.hasBleFeature, words)
            BluetoothField.BLUETOOTH -> when {
                !environment.hasAdapter -> words.noAdapter
                !environment.isBluetoothOn -> words.off
                !environment.hasLeScanner -> words.onWithoutScanner
                else -> words.on
            }
            BluetoothField.LOCATION_SWITCH -> environment.isLocationOn?.let { if (it) words.on else words.off }
            BluetoothField.LOCATION_PERMISSION -> grantedDenied(environment.hasLocationPermission, words)
            BluetoothField.NEARBY_PERMISSION -> environment.hasNearbyPermission?.let { grantedDenied(it, words) }
            BluetoothField.SCAN_LE -> scan?.let { if (it.block != null) words.notStarted else words.devicesFound(it.leDevices.toString()) }
            BluetoothField.SCAN_DISCOVERY -> scan?.let { discoveryValue(it, words) }
            BluetoothField.SCAN_FAILURE -> scan?.let { it.failureCode?.toString() ?: words.none }
            BluetoothField.SCAN_THROTTLED -> scan?.let { yesNo(it.isThrottled, words) }
        }
    }
}

private fun discoveryValue(scan: ScanReport, words: BluetoothWords): String {
    val sawAnything = scan.discoveryDevices > 0 || scan.classicOnlyDevices > 0
    return if (sawAnything || scan.isDiscoveryStarted) {
        words.discoveryFound(scan.discoveryDevices.toString(), scan.classicOnlyDevices.toString())
    } else {
        words.notStarted
    }
}

private fun yesNo(value: Boolean, words: BluetoothWords) = if (value) words.yes else words.no

private fun grantedDenied(value: Boolean, words: BluetoothWords) = if (value) words.granted else words.denied

/** The Bluetooth block as a section of label/value rows, like the Adapter section above it. */
@Composable
internal fun BluetoothSection(diagnostics: BluetoothDiagnostics) {
    val resources = LocalContext.current.resources
    val words = BluetoothWords(
        yes = stringResource(R.string.value_yes),
        no = stringResource(R.string.value_no),
        on = stringResource(R.string.value_on),
        off = stringResource(R.string.value_off),
        granted = stringResource(R.string.value_granted),
        denied = stringResource(R.string.value_denied),
        none = stringResource(R.string.value_none),
        noAdapter = stringResource(R.string.value_bt_unavailable),
        onWithoutScanner = stringResource(R.string.value_bt_on_no_scanner),
        notStarted = stringResource(R.string.value_bt_scan_blocked),
        android = { release, api -> resources.getString(R.string.value_bt_android, release, api) },
        devicesFound = { count -> resources.getString(R.string.value_bt_devices, count) },
        discoveryFound = { count, classicOnly -> resources.getString(R.string.value_bt_discovery, count, classicOnly) },
    )
    SectionTitle(stringResource(R.string.debug_section_bluetooth))
    HorizontalRule()
    bluetoothValues(diagnostics, words).forEach { (field, value) -> InfoRow(stringResource(field.labelRes), value) }
}
