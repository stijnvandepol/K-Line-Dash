package nl.stijnvdpol.klinedash.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.transport.TransportKind
import nl.stijnvdpol.klinedash.data.ConnectionSettings
import nl.stijnvdpol.klinedash.service.detail
import nl.stijnvdpol.klinedash.service.headline
import nl.stijnvdpol.klinedash.transport.BtDeviceInfo
import nl.stijnvdpol.klinedash.transport.needsBluetoothPermissions
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing
import nl.stijnvdpol.klinedash.ui.theme.accentColor

private const val TAG = "ConnectionScreen"
private val ValidPorts = 1..65535
private const val MAX_PORT_DIGITS = 5
private const val CONTROLS_WEIGHT = 0.42f
private const val DETAILS_WEIGHT = 0.58f

private val StatusDotSize = 20.dp
private val StatusButtonWidth = 176.dp
private val FieldLabelWidth = 264.dp
private val RowGap = 4.dp

/** Text inside list rows sits this far from the edge of its (raised) pill; titles and hints match it. */
private val RowInset = Spacing.panelPadding

/** The port typed so far, or null while it is not (yet) a usable TCP port. */
private fun String.toValidPort(): Int? = toIntOrNull()?.takeIf { it in ValidPorts }

/**
 * @param onConnect called with whether the chosen transport needs Bluetooth permissions; the
 *   caller checks/requests them and then starts the service.
 * @param onScanRequested called before a BLE scan; returns false when permissions are missing
 *   (the caller then shows the permission dialog and the user taps "Zoeken" again).
 * @param permissionRevision changes whenever a permission result came in, to refresh device lists.
 */
@Composable
fun ConnectionScreen(
    onConnect: (needsBluetooth: Boolean) -> Unit,
    onScanRequested: () -> Boolean,
    permissionRevision: Int,
) {
    val vm = graphViewModel { ConnectionViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val s = settings ?: return

    LaunchedEffect(s.kind, permissionRevision) {
        if (s.kind == TransportKind.BT_CLASSIC) vm.refreshBonded()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(Spacing.gutter),
    ) {
        ConnectionStatusCard(
            state = state,
            onConnect = { onConnect(s.kind.needsBluetoothPermissions()) },
            onDisconnect = vm::disconnect,
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.gutter),
        ) {
            ConnectionControls(vm, s, Modifier.weight(CONTROLS_WEIGHT))
            TransportDetails(vm, s, onScanRequested, Modifier.weight(DETAILS_WEIGHT))
        }
    }
}

/** State on one line, details muted on a second; the two actions sit at the right edge. */
@Composable
private fun ConnectionStatusCard(state: ConnectionState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val resources = LocalContext.current.resources
    val colors = KLineTheme.colors
    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(state.accentColor(colors), size = StatusDotSize)
            Spacer(Modifier.width(Spacing.gutter))
            Column(Modifier.weight(1f)) {
                Text(state.headline(resources), style = KLineType.title, color = colors.ink, maxLines = 1)
                Text(state.detail(resources), style = KLineType.body, color = colors.inkMuted, maxLines = 1)
            }
            Spacer(Modifier.width(Spacing.gutter))
            SecondaryButton(
                stringResource(R.string.btn_disconnect), onDisconnect,
                Modifier.width(StatusButtonWidth), enabled = state !is ConnectionState.Idle,
            )
            Spacer(Modifier.width(Spacing.gutter))
            PrimaryButton(stringResource(R.string.btn_connect), onConnect, Modifier.width(StatusButtonWidth))
        }
    }
}

/** Left column: which kind of adapter, auto-connect, and forgetting the saved protocol. */
@Composable
private fun ConnectionControls(vm: ConnectionViewModel, s: ConnectionSettings, modifier: Modifier) {
    val notice by vm.notice.collectAsStateWithLifecycle()
    Column(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(Spacing.gutter),
    ) {
        Panel(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(RowGap)) {
                SectionTitle(stringResource(R.string.section_adapter_kind), Modifier.padding(start = RowInset, bottom = Spacing.grid))
                TransportRadio(R.string.transport_simulator, TransportKind.SIMULATOR, s, vm)
                TransportRadio(R.string.transport_bt_classic, TransportKind.BT_CLASSIC, s, vm)
                TransportRadio(R.string.transport_ble, TransportKind.BLE, s, vm)
                TransportRadio(R.string.transport_wifi, TransportKind.WIFI, s, vm)
                SwitchRow(stringResource(R.string.auto_connect), s.autoConnect, vm::setAutoConnect)
            }
        }
        // The confirmation replaces the label for a few seconds: feedback right where the finger is.
        SecondaryButton(notice ?: stringResource(R.string.btn_forget_protocol), vm::forgetProtocol, Modifier.fillMaxWidth())
    }
}

@Composable
private fun TransportRadio(labelRes: Int, kind: TransportKind, s: ConnectionSettings, vm: ConnectionViewModel) {
    RadioRow(stringResource(labelRes), selected = s.kind == kind, onClick = { vm.selectKind(kind) })
}

/** Right column: what the selected transport needs (device list, BLE scan or Wi-Fi address). */
@Composable
private fun TransportDetails(
    vm: ConnectionViewModel,
    s: ConnectionSettings,
    onScanRequested: () -> Boolean,
    modifier: Modifier,
) {
    val bonded by vm.bondedDevices.collectAsStateWithLifecycle()
    val bleResults by vm.bleResults.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val engineRunning by vm.engineRunning.collectAsStateWithLifecycle()
    Panel(modifier.fillMaxHeight()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.gutter)) {
            when (s.kind) {
                TransportKind.SIMULATOR -> {
                    SectionTitle(stringResource(R.string.section_simulator), Modifier.padding(start = RowInset))
                    Hint(stringResource(R.string.sim_hint))
                    SwitchRow(
                        label = stringResource(R.string.sim_engine_title),
                        checked = engineRunning,
                        onChange = vm::setEngineRunning,
                        supportingText = stringResource(if (engineRunning) R.string.sim_engine_on else R.string.sim_engine_off),
                    )
                }

                TransportKind.BT_CLASSIC -> {
                    SectionTitle(stringResource(R.string.section_devices_paired), Modifier.padding(start = RowInset))
                    Hint(stringResource(R.string.bt_hint))
                    BluetoothSettingsRow(onRefresh = vm::refreshBonded)
                    DeviceList(
                        devices = bonded,
                        selectedAddress = s.btAddress,
                        emptyText = stringResource(R.string.bt_no_devices),
                        onSelect = vm::selectBtDevice,
                    )
                }

                TransportKind.BLE -> {
                    SectionTitle(stringResource(R.string.section_devices_nearby), Modifier.padding(start = RowInset))
                    Hint(stringResource(R.string.ble_hint))
                    SecondaryButton(
                        stringResource(if (scanning) R.string.ble_scanning else R.string.ble_scan),
                        onClick = { if (onScanRequested()) vm.startScan() },
                        enabled = !scanning,
                    )
                    DeviceList(
                        devices = bleResults,
                        selectedAddress = s.bleAddress,
                        emptyText = stringResource(R.string.ble_no_results),
                        onSelect = vm::selectBleDevice,
                        // A previously chosen device is shown even before the first scan.
                        pinnedName = s.bleName,
                    )
                }

                TransportKind.WIFI -> {
                    SectionTitle(stringResource(R.string.section_network), Modifier.padding(start = RowInset))
                    WifiFields(s.wifiHost, s.wifiPort, vm::setWifi)
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = KLineType.body, color = KLineTheme.colors.inkMuted, modifier = Modifier.padding(horizontal = RowInset))
}

@Composable
private fun BluetoothSettingsRow(onRefresh: () -> Unit) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gutter)) {
        SecondaryButton(
            stringResource(R.string.btn_open_bt_settings),
            onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    // Some head units strip the stock settings app; nothing sensible to fall back to.
                    Log.w(TAG, "No Bluetooth settings screen on this device", e)
                }
            },
        )
        SecondaryButton(stringResource(R.string.btn_refresh), onRefresh)
    }
}

@Composable
private fun DeviceList(
    devices: List<BtDeviceInfo>,
    selectedAddress: String?,
    emptyText: String,
    onSelect: (BtDeviceInfo) -> Unit,
    pinnedName: String? = null,
) {
    if (devices.isEmpty()) {
        if (selectedAddress != null && pinnedName != null) {
            DeviceRow(pinnedName, selectedAddress, looksLikeObd = false, selected = true, onClick = {})
        }
        Hint(emptyText)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        items(devices, key = { it.address }) { device ->
            DeviceRow(
                name = device.name,
                address = device.address,
                looksLikeObd = device.looksLikeObd,
                selected = device.address == selectedAddress,
                onClick = { onSelect(device) },
            )
        }
    }
}

/** Chosen device = raised panel and "Gekozen" in ink; a likely OBD adapter says so in words. */
@Composable
private fun DeviceRow(name: String, address: String, looksLikeObd: Boolean, selected: Boolean, onClick: () -> Unit) {
    val colors = KLineTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Spacing.controlRadius))
            .background(if (selected) colors.panelRaised else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = Spacing.touchMin)
            .padding(horizontal = Spacing.panelPadding, vertical = Spacing.grid),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = KLineType.body, color = colors.ink, maxLines = 1)
            Text(address, style = KLineType.label, color = colors.inkMuted, maxLines = 1)
        }
        if (looksLikeObd) {
            Text(stringResource(R.string.device_obd_badge), style = KLineType.label, color = colors.inkMuted)
        }
        if (selected) {
            Text(
                stringResource(R.string.device_selected),
                style = KLineType.label,
                color = colors.ink,
                modifier = Modifier.padding(start = Spacing.gutter),
            )
        }
    }
}

/** Two fields with one fixed label width, so the fields start at the same x. */
@Composable
private fun WifiFields(initialHost: String, initialPort: Int, onChange: (String, Int) -> Unit) {
    // Local state is the source of truth while typing; the database write is debounced in the ViewModel.
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort.toString()) }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.gutter)) {
        Hint(stringResource(R.string.wifi_hint))
        FieldRow(stringResource(R.string.wifi_host)) {
            FormTextField(
                value = host,
                onValueChange = { value ->
                    host = value
                    port.toValidPort()?.let { onChange(value.trim(), it) }
                },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }
        FieldRow(stringResource(R.string.wifi_port)) {
            FormTextField(
                value = port,
                onValueChange = { value ->
                    val digits = value.filter { it.isDigit() }.take(MAX_PORT_DIGITS)
                    port = digits
                    digits.toValidPort()?.let { onChange(host.trim(), it) }
                },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }
}

@Composable
private fun FieldRow(label: String, field: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label, style = KLineType.body, color = KLineTheme.colors.ink,
            modifier = Modifier.width(FieldLabelWidth).padding(start = RowInset),
        )
        field()
    }
}
