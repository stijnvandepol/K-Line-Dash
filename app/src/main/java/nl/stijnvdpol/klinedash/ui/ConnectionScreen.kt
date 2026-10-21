package nl.stijnvdpol.klinedash.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import nl.stijnvdpol.klinedash.transport.BtDeviceInfo
import nl.stijnvdpol.klinedash.transport.needsBluetoothPermissions

private val MinTouch = 64.dp
private const val TAG = "ConnectionScreen"
private val ValidPorts = 1..65535
private const val MAX_PORT_DIGITS = 5

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
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConnectionStatusCard(state, Modifier.fillMaxWidth())

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ConnectionControls(vm, s, state, onConnect, Modifier.weight(0.42f))
            TransportDetails(vm, s, onScanRequested, Modifier.weight(0.58f))
        }
    }
}

/** Left column: transport choice, auto-connect and the connect/disconnect/forget actions. */
@Composable
private fun ConnectionControls(
    vm: ConnectionViewModel,
    s: ConnectionSettings,
    state: ConnectionState,
    onConnect: (needsBluetooth: Boolean) -> Unit,
    modifier: Modifier,
) {
    val notice by vm.notice.collectAsStateWithLifecycle()
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TransportOption(R.string.transport_simulator, s.kind == TransportKind.SIMULATOR) {
            vm.selectKind(TransportKind.SIMULATOR)
        }
        TransportOption(R.string.transport_bt_classic, s.kind == TransportKind.BT_CLASSIC) {
            vm.selectKind(TransportKind.BT_CLASSIC)
        }
        TransportOption(R.string.transport_ble, s.kind == TransportKind.BLE) {
            vm.selectKind(TransportKind.BLE)
        }
        TransportOption(R.string.transport_wifi, s.kind == TransportKind.WIFI) {
            vm.selectKind(TransportKind.WIFI)
        }

        SwitchRow(stringResource(R.string.auto_connect), s.autoConnect, vm::setAutoConnect)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { onConnect(s.kind.needsBluetoothPermissions()) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MinTouch),
            ) { Text(stringResource(R.string.btn_connect)) }
            OutlinedButton(
                onClick = vm::disconnect,
                enabled = state !is ConnectionState.Idle,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MinTouch),
            ) { Text(stringResource(R.string.btn_disconnect)) }
        }
        OutlinedButton(
            onClick = vm::forgetProtocol,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouch),
        ) { Text(stringResource(R.string.btn_forget_protocol)) }

        notice?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
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
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (s.kind) {
            TransportKind.SIMULATOR -> Hint(stringResource(R.string.sim_hint))

            TransportKind.BT_CLASSIC -> {
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
                Button(
                    onClick = { if (onScanRequested()) vm.startScan() },
                    enabled = !scanning,
                    modifier = Modifier.heightIn(min = MinTouch),
                ) {
                    Text(stringResource(if (scanning) R.string.ble_scanning else R.string.ble_scan))
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

            TransportKind.WIFI -> WifiPanel(s.wifiHost, s.wifiPort, vm::setWifi)
        }
    }
}

@Composable
private fun TransportOption(labelRes: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BluetoothSettingsRow(onRefresh: () -> Unit) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    // Some head units strip the stock settings app; nothing sensible to fall back to.
                    Log.w(TAG, "No Bluetooth settings screen on this device", e)
                }
            },
            modifier = Modifier.heightIn(min = MinTouch),
        ) { Text(stringResource(R.string.btn_open_bt_settings)) }
        OutlinedButton(onClick = onRefresh, modifier = Modifier.heightIn(min = MinTouch)) {
            Text(stringResource(R.string.btn_refresh))
        }
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
            DeviceRow(pinnedName, selectedAddress, highlighted = false, selected = true, onClick = {})
        }
        Hint(emptyText)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(devices, key = { it.address }) { device ->
            DeviceRow(
                name = device.name,
                address = device.address,
                highlighted = device.looksLikeObd,
                selected = device.address == selectedAddress,
                onClick = { onSelect(device) },
            )
        }
    }
}

@Composable
private fun DeviceRow(name: String, address: String, highlighted: Boolean, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (highlighted) scheme.primaryContainer else scheme.surfaceVariant,
        border = if (selected) BorderStroke(3.dp, scheme.primary) else null,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(address, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            if (highlighted) {
                Text(stringResource(R.string.device_obd_badge), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
            }
            if (selected) {
                Text(
                    stringResource(R.string.device_selected),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun WifiPanel(initialHost: String, initialPort: Int, onChange: (String, Int) -> Unit) {
    // Local state is the source of truth while typing; the database write is debounced in the ViewModel.
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort.toString()) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hint(stringResource(R.string.wifi_hint))
        OutlinedTextField(
            value = host,
            onValueChange = { value ->
                host = value
                port.toValidPort()?.let { onChange(value.trim(), it) }
            },
            label = { Text(stringResource(R.string.wifi_host)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { value ->
                val digits = value.filter { it.isDigit() }.take(MAX_PORT_DIGITS)
                port = digits
                digits.toValidPort()?.let { onChange(host.trim(), it) }
            },
            label = { Text(stringResource(R.string.wifi_port)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
