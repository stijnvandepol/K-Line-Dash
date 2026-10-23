package nl.stijnvdpol.klinedash.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import nl.stijnvdpol.klinedash.ui.theme.LocalIsKeyboardCrowding
import nl.stijnvdpol.klinedash.ui.theme.Spacing
import nl.stijnvdpol.klinedash.ui.theme.accentColor

private const val TAG = "ConnectionScreen"
private val AdapterColumnWidth = 400.dp
private val AdapterColumnMinWidth = 260.dp
private const val ADAPTER_COLUMN_SHARE = 0.38f
private val FieldLabelWidth = 264.dp
private const val UNSUPPORTED_REASON_MAX_LINES = 3

/**
 * @param onConnect called with whether the chosen transport needs Bluetooth permissions; the
 *   caller checks/requests them and then starts the service.
 * @param onScanRequested called before a BLE scan; returns false when permissions are missing
 *   (the caller then shows the permission dialog and the user taps "Scan" again).
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val adapterWidth = sideColumnWidth(maxWidth, ADAPTER_COLUMN_SHARE, AdapterColumnMinWidth, AdapterColumnWidth)
        // While the keyboard covers a phone, only the fields stay (the same composables, so typing is not interrupted).
        val isKeyboardCrowding = LocalIsKeyboardCrowding.current
        Row(Modifier.fillMaxSize()) {
            if (!isKeyboardCrowding) {
                AdapterColumn(vm, s, Modifier.width(adapterWidth).fillMaxHeight())
                VerticalRule()
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (!isKeyboardCrowding) {
                    StatusBlock(state, Modifier.fillMaxWidth().heightIn(min = Spacing.sectionHeaderHeight))
                    HorizontalRule()
                    // Right under the status, level with the first adapter row: the state and what to do
                    // about it read as one block. Only the unsupported-adapter help makes the status taller.
                    Actions(
                        vm = vm,
                        state = state,
                        onConnect = { onConnect(s.kind.needsBluetoothPermissions()) },
                        modifier = Modifier.fillMaxWidth().height(Spacing.touchMin),
                    )
                    HorizontalRule()
                }
                TransportDetails(vm, s, onScanRequested, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** Left column: which kind of adapter, and whether to connect at start. */
@Composable
private fun AdapterColumn(vm: ConnectionViewModel, s: ConnectionSettings, modifier: Modifier) {
    Column(modifier) {
        SectionTitle(stringResource(R.string.section_adapter_kind), Modifier.height(Spacing.sectionHeaderHeight))
        HorizontalRule()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            TransportRow(R.string.transport_simulator, TransportKind.SIMULATOR, s, vm)
            TransportRow(R.string.transport_bt_classic, TransportKind.BT_CLASSIC, s, vm)
            TransportRow(R.string.transport_ble, TransportKind.BLE, s, vm)
            TransportRow(R.string.transport_wifi, TransportKind.WIFI, s, vm)
            SwitchRow(stringResource(R.string.auto_connect), s.autoConnect, vm::setAutoConnect)
            HorizontalRule()
        }
    }
}

@Composable
private fun TransportRow(@StringRes labelRes: Int, kind: TransportKind, s: ConnectionSettings, vm: ConnectionViewModel) {
    val selected = s.kind == kind
    SelectableRow(selected = selected, onClick = { vm.selectKind(kind) }) {
        Text(
            stringResource(labelRes),
            style = KLineType.body,
            color = if (selected) KLineTheme.colors.ink else KLineTheme.colors.inkMuted,
        )
    }
}

/**
 * State in `title`, details muted below it; the lamp has the colour of the state. An unsupported
 * adapter gets its full reason and the list of adapters that work instead of a one-line detail.
 */
@Composable
private fun StatusBlock(state: ConnectionState, modifier: Modifier) {
    val resources = LocalContext.current.resources
    val colors = KLineTheme.colors
    val unsupported = state.isUnsupportedAdapter()
    Row(modifier.padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical)) {
        // The lamp sits on the line of the title, not in the middle of the two lines.
        StatusDot(state.accentColor(colors), Modifier.padding(top = StatusDotTitleInset))
        Spacer(Modifier.width(Spacing.fieldPaddingHorizontal))
        Column {
            Text(state.headline(resources), style = KLineType.title, color = colors.ink, maxLines = 1)
            Text(
                state.detail(resources),
                style = KLineType.body,
                color = colors.inkMuted,
                maxLines = if (unsupported) UNSUPPORTED_REASON_MAX_LINES else 1,
            )
            if (unsupported) SupportedAdaptersList(Modifier.padding(top = Spacing.fieldPaddingVertical))
        }
    }
}

/** "Adapters that work" as a caps label and the README's list in two columns of `caption`. */
@Composable
internal fun SupportedAdaptersList(modifier: Modifier) {
    val colors = KLineTheme.colors
    Column(modifier) {
        Text(stringResource(R.string.adapters_that_work).uppercase(), style = KLineType.fieldLabel, color = colors.inkMuted)
        Row(Modifier.padding(top = Spacing.grid)) {
            SupportedAdapters.chunked((SupportedAdapters.size + 1) / 2).forEach { column ->
                Column(Modifier.weight(1f)) {
                    column.forEach { Text(it.label, style = KLineType.caption, color = colors.ink, maxLines = 1) }
                }
            }
        }
    }
}

/**
 * Connect, Disconnect and forgetting the saved protocol: one row of 72 dp cells separated by rules,
 * like the button grids on Debug. Connect is the one filled cell while there is no link; once the
 * link is up it stays available (it reconnects) but no longer asks to be tapped.
 */
@Composable
private fun Actions(vm: ConnectionViewModel, state: ConnectionState, onConnect: () -> Unit, modifier: Modifier) {
    val notice by vm.notice.collectAsStateWithLifecycle()
    Row(modifier) {
        val connectModifier = Modifier.weight(1f).fillMaxHeight()
        if (state is ConnectionState.Connected) {
            TextActionButton(stringResource(R.string.btn_connect), onConnect, connectModifier)
        } else {
            PrimaryButton(stringResource(R.string.btn_connect), onConnect, connectModifier)
        }
        VerticalRule()
        TextActionButton(
            stringResource(R.string.btn_disconnect), vm::disconnect, Modifier.weight(1f).fillMaxHeight(),
            enabled = state !is ConnectionState.Idle,
        )
        VerticalRule()
        // The confirmation replaces the label for a few seconds: feedback right where the finger is.
        TextActionButton(notice ?: stringResource(R.string.btn_forget_protocol), vm::forgetProtocol, Modifier.weight(1f).fillMaxHeight())
    }
}

/** What the selected transport needs: device list, BLE scan or Wi-Fi address. */
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
    Column(modifier.verticalScroll(rememberScrollState())) {
        when (s.kind) {
            TransportKind.SIMULATOR -> {
                SectionTitle(stringResource(R.string.section_simulator))
                Hint(stringResource(R.string.sim_hint))
                HorizontalRule()
                SwitchRow(stringResource(R.string.sim_engine_title), engineRunning, vm::setEngineRunning)
                HorizontalRule()
            }

            TransportKind.BT_CLASSIC -> {
                SectionTitle(stringResource(R.string.section_devices_paired))
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
                SectionTitle(stringResource(R.string.section_devices_nearby))
                Hint(stringResource(R.string.ble_hint))
                ButtonRow {
                    SecondaryButton(
                        stringResource(if (scanning) R.string.ble_scanning else R.string.ble_scan),
                        onClick = { if (onScanRequested()) vm.startScan() },
                        modifier = Modifier.weight(1f),
                        enabled = !scanning,
                    )
                }
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
                SectionTitle(stringResource(R.string.section_network))
                Hint(stringResource(R.string.wifi_hint))
                WifiFields(s.wifiHost, s.wifiPort, vm::setWifi)
            }
        }
    }
}

/** Muted guidance under a section title; [modifier] adds a top inset where it follows a rule. */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = KLineType.body,
        color = KLineTheme.colors.inkMuted,
        modifier = modifier.padding(horizontal = Spacing.fieldPaddingHorizontal).padding(bottom = Spacing.fieldPaddingVertical),
    )
}

@Composable
internal fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(Spacing.fieldPaddingHorizontal),
        horizontalArrangement = Arrangement.spacedBy(Spacing.fieldPaddingHorizontal),
        content = content,
    )
}

/**
 * Opens a screen of Android's own settings ([action], e.g. [Settings.ACTION_BLUETOOTH_SETTINGS]).
 * Some head units strip the stock settings app; false then, and the caller says so.
 */
internal fun openSystemSettings(context: Context, action: String): Boolean = try {
    context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    Log.w(TAG, "No settings screen for $action on this device", e)
    false
}

@Composable
private fun BluetoothSettingsRow(onRefresh: () -> Unit) {
    val context = LocalContext.current
    ButtonRow {
        SecondaryButton(
            stringResource(R.string.btn_open_bt_settings),
            onClick = { openSystemSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS) },
            modifier = Modifier.weight(1f),
        )
        SecondaryButton(stringResource(R.string.btn_refresh), onRefresh, Modifier.weight(1f))
    }
}

@Composable
internal fun DeviceList(
    devices: List<BtDeviceInfo>,
    selectedAddress: String?,
    emptyText: String,
    onSelect: (BtDeviceInfo) -> Unit,
    pinnedName: String? = null,
) {
    HorizontalRule()
    if (devices.isEmpty()) {
        if (selectedAddress != null && pinnedName != null) {
            DeviceRow(BtDeviceInfo(pinnedName, selectedAddress, looksLikeObd = false), selected = true, onClick = {})
        }
        Hint(emptyText, Modifier.padding(top = Spacing.fieldPaddingVertical))
        return
    }
    devices.forEach { device ->
        DeviceRow(device, selected = device.address == selectedAddress, onClick = { onSelect(device) })
    }
}

/**
 * Chosen device = white bar and white name; a likely OBD adapter says so in words. A dongle known to
 * work only with its own app gets a caution caption but stays selectable: a device that answers is used.
 */
@Composable
internal fun DeviceRow(device: BtDeviceInfo, selected: Boolean, onClick: () -> Unit) {
    val colors = KLineTheme.colors
    SelectableRow(selected = selected, onClick = onClick) {
        Column(Modifier.weight(1f)) {
            Text(device.name, style = KLineType.body, color = if (selected) colors.ink else colors.inkMuted, maxLines = 1)
            if (device.knownAppLocked) {
                Text(stringResource(R.string.device_may_not_work), style = KLineType.caption, color = colors.caution, maxLines = 1)
            }
            Text(device.address, style = KLineType.caption, color = colors.inkMuted, maxLines = 1)
        }
        if (device.looksLikeObd) {
            Text(stringResource(R.string.device_obd_badge), style = KLineType.caption, color = colors.inkMuted)
        }
    }
}

/** Two fields with one fixed label width, so the fields start at the same x. */
@Composable
internal fun WifiFields(initialHost: String, initialPort: Int, onChange: (String, Int) -> Unit) {
    // Local state is the source of truth while typing; the database write is debounced in the ViewModel.
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort.toString()) }

    FieldRow(stringResource(R.string.wifi_host)) {
        FormTextField(
            value = host,
            onValueChange = { value ->
                host = value
                validPort(port)?.let { onChange(value.trim(), it) }
            },
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
    }
    FieldRow(stringResource(R.string.wifi_port)) {
        FormTextField(
            value = port,
            onValueChange = { value ->
                port = portDigits(value)
                validPort(port)?.let { onChange(host.trim(), it) }
            },
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

@Composable
private fun FieldRow(label: String, field: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = KLineType.body, color = KLineTheme.colors.ink, modifier = Modifier.width(FieldLabelWidth))
        field()
    }
}
