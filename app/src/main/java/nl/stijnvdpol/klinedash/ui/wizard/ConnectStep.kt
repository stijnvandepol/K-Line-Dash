package nl.stijnvdpol.klinedash.ui.wizard

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.ui.TwoPane
import nl.stijnvdpol.klinedash.core.transport.TransportKind
import nl.stijnvdpol.klinedash.data.ConnectionSettings
import nl.stijnvdpol.klinedash.ui.ButtonRow
import nl.stijnvdpol.klinedash.ui.ConnectionViewModel
import nl.stijnvdpol.klinedash.ui.DeviceList
import nl.stijnvdpol.klinedash.ui.HorizontalRule
import nl.stijnvdpol.klinedash.ui.PermissionRequests
import nl.stijnvdpol.klinedash.ui.SectionTitle
import nl.stijnvdpol.klinedash.ui.SecondaryButton
import nl.stijnvdpol.klinedash.ui.WifiFields
import nl.stijnvdpol.klinedash.ui.openSystemSettings
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.Spacing

/**
 * Step 3: get the chosen adapter paired, switched on or joined, then pick it. The left half says
 * what to do, in order; the right half is the list (or the address) to choose from. The Bluetooth
 * permission is asked for here, with its reason next to the button that needs it, and nowhere before.
 */
@Composable
internal fun ConnectStep(connection: ConnectionViewModel, permissions: PermissionRequests) {
    val settings by connection.settings.collectAsStateWithLifecycle()
    val bonded by connection.bondedDevices.collectAsStateWithLifecycle()
    val bleResults by connection.bleResults.collectAsStateWithLifecycle()
    val scanning by connection.scanning.collectAsStateWithLifecycle()
    var resumes by remember { mutableIntStateOf(0) }
    var isSettingsMissing by remember { mutableStateOf(false) }
    val s = settings ?: return
    val hasPermission = remember(permissions.revision, resumes) { permissions.hasBluetooth() }

    // The paired list is read again after a permission answer and when the user comes back from Android's settings.
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    LaunchedEffect(s.kind, permissions.revision, resumes) {
        if (s.kind == TransportKind.BT_CLASSIC) connection.refreshBonded()
    }
    // Looking for the adapter starts by itself once Android allows it.
    LaunchedEffect(s.kind, permissions.revision) {
        if (s.kind == TransportKind.BLE && permissions.hasBluetooth() && bleResults.isEmpty()) connection.startScan()
    }

    val context = LocalContext.current
    val openSettings = { action: String -> isSettingsMissing = !openSystemSettings(context, action) }
    TwoPane(
        left = {
            StepTitle(stringResource(s.kind.connectTitleRes()))
            NumberedStep(1, stringResource(R.string.wiz_step_plug_in))
            when (s.kind) {
                TransportKind.BT_CLASSIC -> BluetoothInstructions(hasPermission, permissions, connection::refreshBonded) { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
                TransportKind.BLE -> BleInstructions(hasPermission, scanning, permissions, connection)
                TransportKind.WIFI -> WifiInstructions { openSettings(Settings.ACTION_WIFI_SETTINGS) }
                TransportKind.SIMULATOR -> Unit
            }
            if (isSettingsMissing) StepText(stringResource(R.string.wiz_settings_missing), color = KLineTheme.colors.caution)
        },
        right = {
            when (s.kind) {
                TransportKind.BT_CLASSIC -> {
                    SectionTitle(stringResource(R.string.section_devices_paired))
                    DeviceList(bonded, s.btAddress, stringResource(R.string.bt_no_devices), connection::selectBtDevice)
                }
                TransportKind.BLE -> {
                    SectionTitle(stringResource(R.string.section_devices_nearby))
                    DeviceList(bleResults, s.bleAddress, stringResource(R.string.ble_no_results), connection::selectBleDevice, pinnedName = s.bleName)
                }
                TransportKind.WIFI -> {
                    SectionTitle(stringResource(R.string.section_network))
                    WifiFields(s.wifiHost, s.wifiPort, connection::setWifi)
                }
                TransportKind.SIMULATOR -> Unit
            }
            ChooseHint(s)
        },
    )
}

private fun TransportKind.connectTitleRes(): Int = when (this) {
    TransportKind.BLE -> R.string.wiz_connect_title_ble
    TransportKind.WIFI -> R.string.wiz_connect_title_wifi
    TransportKind.BT_CLASSIC, TransportKind.SIMULATOR -> R.string.wiz_connect_title_bt
}

@Composable
private fun BluetoothInstructions(
    hasPermission: Boolean,
    permissions: PermissionRequests,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    NumberedStep(2, stringResource(R.string.wiz_bt_step_pair))
    ButtonRow {
        SecondaryButton(stringResource(R.string.btn_open_bt_settings), onOpenSettings, Modifier.weight(1f))
        SecondaryButton(stringResource(R.string.btn_refresh), onRefresh, Modifier.weight(1f))
    }
    NumberedStep(3, stringResource(R.string.wiz_bt_step_choose))
    if (!hasPermission) PermissionNotice(permissions)
}

@Composable
private fun BleInstructions(hasPermission: Boolean, scanning: Boolean, permissions: PermissionRequests, connection: ConnectionViewModel) {
    NumberedStep(2, stringResource(R.string.wiz_ble_step_button))
    NumberedStep(3, stringResource(R.string.wiz_ble_step_scan))
    if (!hasPermission) PermissionReason()
    ButtonRow {
        SecondaryButton(
            stringResource(if (scanning) R.string.ble_scanning else R.string.ble_scan),
            onClick = { if (permissions.isReadyToScan()) connection.startScan() },
            modifier = Modifier.weight(1f),
            enabled = !scanning,
        )
    }
}

@Composable
private fun WifiInstructions(onOpenSettings: () -> Unit) {
    NumberedStep(2, stringResource(R.string.wiz_wifi_step_join))
    ButtonRow { SecondaryButton(stringResource(R.string.btn_open_wifi_settings), onOpenSettings, Modifier.weight(1f)) }
    NumberedStep(3, stringResource(R.string.wiz_wifi_step_address))
}

/** The reason for the permission dialog, in one line, before the user taps what opens it. */
@Composable
private fun PermissionReason() {
    val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.wiz_permission_reason_nearby else R.string.wiz_permission_reason_location
    StepText(stringResource(reason), color = KLineTheme.colors.ink)
}

/** The reason and the button that opens Android's permission dialog. */
@Composable
private fun PermissionNotice(permissions: PermissionRequests) {
    Column(Modifier.fillMaxWidth().padding(top = Spacing.grid)) {
        PermissionReason()
        ButtonRow { SecondaryButton(stringResource(R.string.wiz_permission_allow), permissions::requestBluetooth, Modifier.weight(1f)) }
    }
    HorizontalRule()
}

/** Under the list, until something is chosen: why "Next" waits. */
@Composable
private fun ChooseHint(settings: ConnectionSettings) {
    if (!isAdapterChosen(settings)) StepText(stringResource(R.string.wiz_choose_adapter_hint), Modifier.padding(top = Spacing.grid))
}
