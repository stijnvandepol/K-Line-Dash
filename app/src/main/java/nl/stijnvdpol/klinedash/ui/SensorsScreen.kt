package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.obd.PollStats
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.obd.Signal
import nl.stijnvdpol.klinedash.service.formatNumber
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private const val DEMAND_OWNER = "sensors"
private const val ValueColumnSample = "88,888"
private val UnitColumnWidth = 72.dp
private val RateColumnWidth = 144.dp
private val RowPadding = 16.dp
private val ColumnGap = 12.dp

/** Every signal the car supports, with its live value: a table, name left, value right. */
@Composable
fun SensorsScreen(onOpenConnection: () -> Unit) {
    val vm = graphViewModel { LiveDataViewModel() }
    val poller by vm.poller.collectAsStateWithLifecycle()
    val supportedPids by vm.supportedPids.collectAsStateWithLifecycle()
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val signals = remember(supportedPids) { supportedPids?.let(::supportedSignals).orEmpty() }
    DemandWhileVisible(poller, DEMAND_OWNER, remember(signals) { signals.mapTo(HashSet()) { it.id } })

    Panel(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.screenMargin),
        padding = 0.dp,
    ) {
        when {
            connection !is ConnectionState.Connected -> EmptyState(
                message = stringResource(R.string.sensors_not_connected),
                actionLabel = stringResource(R.string.btn_go_to_connection),
                onAction = onOpenConnection,
            )
            supportedPids == null -> EmptyState(stringResource(R.string.sensors_detecting))
            else -> SensorTable(
                signals = signals,
                readings = vm.readings.collectAsStateWithLifecycle(),
                stats = vm.stats.collectAsStateWithLifecycle(),
                nowMs = rememberNowMs(),
            )
        }
    }
}

@Composable
private fun EmptyState(message: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.gutter, Alignment.CenterVertically),
    ) {
        Text(message, style = KLineType.body, color = KLineTheme.colors.inkMuted, textAlign = TextAlign.Center)
        if (actionLabel != null) PrimaryButton(actionLabel, onAction)
    }
}

@Composable
private fun SensorTable(
    signals: List<Signal>,
    readings: State<Map<String, Reading>>,
    stats: State<PollStats>,
    nowMs: State<Long>,
) {
    Column(Modifier.fillMaxSize()) {
        TableHeader()
        Divider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(signals, key = { it.id }) { signal -> SensorRow(signal, readings, stats, nowMs) }
        }
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = RowPadding)
            .height(Spacing.dividerThickness)
            .background(KLineTheme.colors.line),
    )
}

@Composable
private fun TableHeader() {
    val muted = KLineTheme.colors.inkMuted
    val valueWidth = rememberTextWidth(ValueColumnSample, KLineType.rowValue)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.listRowMin)
            .padding(horizontal = RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.sensors_column_name), style = KLineType.label, color = muted, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.sensors_column_value),
            style = KLineType.label,
            color = muted,
            textAlign = TextAlign.End,
            modifier = Modifier.width(valueWidth),
        )
        Box(Modifier.width(UnitColumnWidth + ColumnGap))
        Text(
            stringResource(R.string.sensors_column_rate),
            style = KLineType.label,
            color = muted,
            textAlign = TextAlign.End,
            modifier = Modifier.width(RateColumnWidth),
        )
    }
}

@Composable
private fun SensorRow(signal: Signal, readings: State<Map<String, Reading>>, stats: State<PollStats>, nowMs: State<Long>) {
    val colors = KLineTheme.colors
    val reading = rememberReading(readings, signal.id)
    val stale = rememberIsStale(reading, nowMs, signal)
    val rate by remember(signal) { derivedStateOf { stats.value.signalHz[signal.id]?.let { formatNumber(it, 1) } } }
    Column {
        // The row is centred in its minimum height as a whole, so all four texts can share one baseline.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.listRowMin)
                .padding(horizontal = RowPadding),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    signal.name, style = KLineType.body, color = colors.ink, maxLines = 1,
                    modifier = Modifier.weight(1f).alignByBaseline(),
                )
                ValueText(
                    signal, reading, stale, KLineType.rowValue, TextAlign.End,
                    Modifier.alignByBaseline(), sample = ValueColumnSample,
                )
                Text(
                    signal.unit, style = KLineType.label, color = colors.inkMuted, maxLines = 1,
                    modifier = Modifier.padding(start = ColumnGap).width(UnitColumnWidth).alignByBaseline(),
                )
                Text(
                    rate?.let { stringResource(R.string.sensors_rate, it) } ?: MISSING_VALUE,
                    style = KLineType.label, color = colors.inkMuted, textAlign = TextAlign.End, maxLines = 1,
                    modifier = Modifier.width(RateColumnWidth).alignByBaseline(),
                )
            }
        }
        Divider()
    }
}
