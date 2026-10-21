package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import kotlin.math.ceil

private const val DEMAND_OWNER = "sensors"
/** The widest value in the table: five digits (distance with the MIL on goes up to 65535 km). */
private const val VALUE_COLUMN_SAMPLE = "88888"
private val UnitColumnWidth = 64.dp
private val RateColumnWidth = 88.dp
private val ColumnGap = 12.dp
private val EmptyStateButtonWidth = 256.dp

/** From this width the table is split into two side-by-side halves, so 18 sensors fit without scrolling. */
private val TwoColumnMinWidth = 1_000.dp
private const val WIDE_COLUMNS = 2

/** Every signal the car supports, with its live value: a table, name left, value right. */
@Composable
fun SensorsScreen(onOpenConnection: () -> Unit) {
    val vm = graphViewModel { LiveDataViewModel() }
    val poller by vm.poller.collectAsStateWithLifecycle()
    val supportedPids by vm.supportedPids.collectAsStateWithLifecycle()
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val signals = remember(supportedPids) { supportedPids?.let(::supportedSignals).orEmpty() }
    DemandWhileVisible(poller, DEMAND_OWNER, remember(signals) { signals.mapTo(HashSet()) { it.id } })

    when {
        connection !is ConnectionState.Connected -> EmptyState(
            message = stringResource(R.string.sensors_not_connected),
            actionLabel = stringResource(R.string.btn_go_to_connection),
            onAction = onOpenConnection,
        )
        supportedPids == null -> EmptyState(stringResource(R.string.sensors_detecting))
        else -> {
            val readings = vm.readings.collectAsStateWithLifecycle()
            SensorTable(
                signals = signals,
                readings = readings,
                stats = vm.stats.collectAsStateWithLifecycle(),
                nowMs = rememberNowMs(),
                engineRunning = rememberEngineRunning(readings),
            )
        }
    }
}

@Composable
private fun EmptyState(message: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.grid * 3, Alignment.CenterVertically),
    ) {
        Text(message, style = KLineType.body, color = KLineTheme.colors.inkMuted, textAlign = TextAlign.Center)
        if (actionLabel != null) PrimaryButton(actionLabel, onAction, Modifier.width(EmptyStateButtonWidth))
    }
}

/**
 * Reads like one table split in half: the first half of the sensors in the left column, the rest in
 * the right one. On a narrow screen it is a single column.
 */
@Composable
private fun SensorTable(
    signals: List<Signal>,
    readings: State<Map<String, Reading>>,
    stats: State<PollStats>,
    nowMs: State<Long>,
    engineRunning: State<Boolean>,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (maxWidth >= TwoColumnMinWidth) WIDE_COLUMNS else 1
        val rowCount = ceil(signals.size / columns.toFloat()).toInt()
        Column(Modifier.fillMaxSize()) {
            TableRow(columns) { TableHeader() }
            HorizontalRule()
            LazyColumn(Modifier.fillMaxSize()) {
                items(rowCount, key = { signals[it].id }) { row ->
                    TableRow(columns) { column ->
                        signals.getOrNull(row + column * rowCount)?.let { signal ->
                            SensorCell(signal, readings, stats, nowMs, engineRunning)
                        }
                    }
                    HorizontalRule()
                }
            }
        }
    }
}

/** One table row of [columns] cells separated by vertical rules; a missing cell stays empty. */
@Composable
private fun TableRow(columns: Int, cell: @Composable RowScope.(column: Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(Spacing.listRowMin)) {
        repeat(columns) { column ->
            if (column > 0) VerticalRule()
            // Centred as a whole: the cells align by baseline, which would pin them to the top of the row.
            Box(
                Modifier.weight(1f).fillMaxHeight().padding(horizontal = Spacing.fieldPaddingHorizontal),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(Modifier.fillMaxWidth()) { cell(column) }
            }
        }
    }
}

@Composable
private fun RowScope.TableHeader() {
    val muted = KLineTheme.colors.inkMuted
    val valueWidth = rememberTextWidth(VALUE_COLUMN_SAMPLE, KLineType.rowValue)
    Text(stringResource(R.string.sensors_column_name).uppercase(), style = KLineType.fieldLabel, color = muted, modifier = Modifier.weight(1f))
    Text(
        stringResource(R.string.sensors_column_value).uppercase(),
        style = KLineType.fieldLabel,
        color = muted,
        textAlign = TextAlign.End,
        modifier = Modifier.width(valueWidth),
    )
    Spacer(Modifier.width(UnitColumnWidth + ColumnGap))
    Text(
        stringResource(R.string.sensors_column_rate).uppercase(),
        style = KLineType.fieldLabel,
        color = muted,
        textAlign = TextAlign.End,
        modifier = Modifier.width(RateColumnWidth),
    )
}

@Composable
private fun RowScope.SensorCell(
    signal: Signal,
    readings: State<Map<String, Reading>>,
    stats: State<PollStats>,
    nowMs: State<Long>,
    engineRunning: State<Boolean>,
) {
    val colors = KLineTheme.colors
    val reading = rememberReading(readings, signal.id)
    val stale = rememberIsStale(reading, nowMs, signal)
    val tone = rememberValueTone(signal, reading, engineRunning)
    val rate by remember(signal) { derivedStateOf { stats.value.signalHz[signal.id]?.let { formatNumber(it, 1) } } }
    // All four texts share one baseline, so the row reads as a line.
    Text(signal.name, style = KLineType.body, color = colors.ink, maxLines = 1, modifier = Modifier.weight(1f).alignByBaseline())
    ValueText(signal, reading, stale, tone, KLineType.rowValue, Modifier.alignByBaseline(), sample = VALUE_COLUMN_SAMPLE)
    Text(
        signal.unit, style = KLineType.caption, color = colors.inkMuted, maxLines = 1,
        modifier = Modifier.padding(start = ColumnGap).width(UnitColumnWidth).alignByBaseline(),
    )
    Text(
        rate?.let { stringResource(R.string.sensors_rate, it) } ?: MISSING_VALUE,
        style = KLineType.caption, color = colors.inkMuted, textAlign = TextAlign.End, maxLines = 1,
        modifier = Modifier.width(RateColumnWidth).alignByBaseline(),
    )
}
