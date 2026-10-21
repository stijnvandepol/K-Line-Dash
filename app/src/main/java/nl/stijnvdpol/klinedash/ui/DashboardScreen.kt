package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import nl.stijnvdpol.klinedash.AppGraph
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.elm.AdapterInfo
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.transport.TransportKind
import nl.stijnvdpol.klinedash.service.ObdServiceState
import nl.stijnvdpol.klinedash.service.formatVoltage

class DashboardViewModel(graph: AppGraph) : ViewModel() {
    val connectionState: StateFlow<ConnectionState> = ObdServiceState.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Idle)

    val isSimulator: StateFlow<Boolean> = graph.settingsRepository.connectionSettings
        .map { it.kind == TransportKind.SIMULATOR }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val engineRunning: StateFlow<Boolean> = ObdServiceState.simulatorEngineRunning

    fun setEngineRunning(running: Boolean) = ObdServiceState.setSimulatorEngineRunning(running)
}

/** Phase 1 placeholder: connection state, adapter info and the simulator engine switch. */
@Composable
fun DashboardScreen() {
    val vm = graphViewModel { DashboardViewModel(it) }
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val isSimulator by vm.isSimulator.collectAsStateWithLifecycle()
    val engineRunning by vm.engineRunning.collectAsStateWithLifecycle()

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConnectionStatusCard(state, Modifier.fillMaxWidth(), large = true)
            if (state is ConnectionState.Idle || state is ConnectionState.Failed) {
                Text(
                    stringResource(R.string.dashboard_not_connected_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.dashboard_phase_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            (state as? ConnectionState.Connected)?.let { AdapterInfoCard(it.info) }
            if (isSimulator) {
                SimulatorCard(engineRunning, onChange = vm::setEngineRunning)
            }
        }
    }
}

@Composable
private fun AdapterInfoCard(info: AdapterInfo) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.dashboard_adapter_title), style = MaterialTheme.typography.titleLarge)
            InfoRow(stringResource(R.string.label_elm_version), info.elmVersion)
            InfoRow(
                stringResource(R.string.label_protocol),
                "${info.protocolName} (${info.protocolNumber})",
            )
            InfoRow(
                stringResource(R.string.label_voltage),
                info.voltage?.let { "${formatVoltage(it)} V" } ?: stringResource(R.string.value_unknown),
            )
            InfoRow(
                stringResource(R.string.label_saved_protocol),
                stringResource(if (info.usedSavedProtocol) R.string.value_yes else R.string.value_no),
            )
            InfoRow(
                stringResource(R.string.label_unsupported),
                info.unsupportedCommands.sorted().joinToString().ifEmpty { stringResource(R.string.value_none) },
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SimulatorCard(engineRunning: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .toggleable(value = engineRunning, role = Role.Switch, onValueChange = onChange)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.sim_engine_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (engineRunning) R.string.sim_engine_on else R.string.sim_engine_off),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = engineRunning, onCheckedChange = null)
        }
    }
}
