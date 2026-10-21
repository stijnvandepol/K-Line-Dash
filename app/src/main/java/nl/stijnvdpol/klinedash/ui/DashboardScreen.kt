package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.obd.PollMode
import nl.stijnvdpol.klinedash.core.obd.PollStats
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.obd.Signal
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.service.detail
import nl.stijnvdpol.klinedash.service.formatNumber
import nl.stijnvdpol.klinedash.service.headline
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing
import nl.stijnvdpol.klinedash.ui.theme.accentColor

private const val DEMAND_OWNER = "dashboard"
private const val TILES_PER_ROW = 2
private val StatusLineHeight = 40.dp
private val StatusGap = 24.dp
private val UnitGap = 8.dp

/**
 * Dashboard: RPM gauge and speed on the left, six tiles on the right (same top and bottom edges),
 * the connection status under the gauge. Asks the poller for exactly what it shows, while visible.
 */
@Composable
fun DashboardScreen() {
    val vm = graphViewModel { LiveDataViewModel() }
    val poller by vm.poller.collectAsStateWithLifecycle()
    val supportedPids by vm.supportedPids.collectAsStateWithLifecycle()
    val tileIds = remember(supportedPids) { dashboardTileIds(supportedPids) }
    DemandWhileVisible(poller, DEMAND_OWNER, remember(tileIds) { dashboardDemandIds(tileIds) })

    // States, not values: only the small composables below read them, so this one never recomposes
    // for a new reading.
    val readings = vm.readings.collectAsStateWithLifecycle()
    val stats = vm.stats.collectAsStateWithLifecycle()
    val connection = vm.connectionState.collectAsStateWithLifecycle()
    val nowMs = rememberNowMs()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(Spacing.gutter),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.gutter),
        ) {
            GaugePanel(readings, stats, nowMs, Modifier.weight(1f).fillMaxHeight())
            TileGrid(tileIds, readings, nowMs, Modifier.weight(1f).fillMaxHeight())
        }
        StatusLine(connection, stats, Modifier.fillMaxWidth().height(StatusLineHeight))
    }
}

@Composable
private fun GaugePanel(
    readings: State<Map<String, Reading>>,
    stats: State<PollStats>,
    nowMs: State<Long>,
    modifier: Modifier,
) {
    val rpmSignal = remember { checkNotNull(Signals.byId(RPM_ID)) }
    val speedSignal = remember { checkNotNull(Signals.byId(SPEED_ID)) }
    val rpm = rememberReading(readings, RPM_ID)
    val rpmStale = rememberIsStale(rpm, nowMs, rpmSignal)
    val speed = rememberReading(readings, SPEED_ID)
    val speedStale = rememberIsStale(speed, nowMs, speedSignal)
    val needleDurationMs = remember(stats) { derivedStateOf { needleDurationMs(stats.value.signalHz[RPM_ID]) } }

    Panel(modifier, padding = Spacing.heroPadding) {
        Column(Modifier.fillMaxSize()) {
            RpmGauge(rpmSignal, rpm, rpmStale, needleDurationMs, Modifier.weight(1f).fillMaxWidth())
            SpeedRow(speedSignal, speed, speedStale, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SpeedRow(signal: Signal, reading: State<Reading?>, stale: State<Boolean>, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
        ValueText(signal, reading, stale, KLineType.largeValue, TextAlign.End, Modifier.alignByBaseline())
        Spacer(Modifier.width(UnitGap))
        Text(
            signal.unit,
            style = KLineType.label,
            color = KLineTheme.colors.inkMuted,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

@Composable
private fun TileGrid(
    tileIds: List<String>,
    readings: State<Map<String, Reading>>,
    nowMs: State<Long>,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.gutter)) {
        tileIds.chunked(TILES_PER_ROW).forEach { rowIds ->
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.gutter),
            ) {
                rowIds.forEach { id ->
                    key(id) { SignalTile(id, readings, nowMs, Modifier.weight(1f).fillMaxHeight()) }
                }
                repeat(TILES_PER_ROW - rowIds.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Label top-left, value and unit on one baseline at the bottom: the same lines in every row. */
@Composable
private fun SignalTile(id: String, readings: State<Map<String, Reading>>, nowMs: State<Long>, modifier: Modifier) {
    val signal = remember(id) { checkNotNull(Signals.byId(id)) }
    val reading = rememberReading(readings, id)
    val stale = rememberIsStale(reading, nowMs, signal)
    val colors = KLineTheme.colors
    Panel(modifier) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(signal.shortName, style = KLineType.label, color = colors.inkMuted, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom) {
                ValueText(signal, reading, stale, KLineType.tileValue, TextAlign.Start, Modifier.alignByBaseline())
                Spacer(Modifier.width(UnitGap))
                Text(
                    signal.unit,
                    style = KLineType.label,
                    color = colors.inkMuted,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
}

/** Dot, state and the measured request rate; information, not a control. */
@Composable
private fun StatusLine(
    connection: State<ConnectionState>,
    stats: State<PollStats>,
    modifier: Modifier,
) {
    val resources = LocalContext.current.resources
    val colors = KLineTheme.colors
    val state = connection.value
    val pollStats = stats.value
    val secondary = when {
        state is ConnectionState.Connected -> when (pollStats.mode) {
            PollMode.ENGINE_OFF -> stringResource(R.string.status_engine_off)
            PollMode.WAITING -> stringResource(R.string.status_detecting)
            PollMode.ACTIVE -> stringResource(R.string.status_rate, formatNumber(pollStats.requestsPerSecond, 1))
        }
        state is ConnectionState.Idle -> stringResource(R.string.status_hint_idle)
        state is ConnectionState.Failed -> stringResource(R.string.status_hint_failed)
        else -> state.detail(resources)
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusDot(state.accentColor(colors))
        Spacer(Modifier.width(UnitGap * 2))
        Text(state.headline(resources), style = KLineType.body, color = colors.ink, maxLines = 1)
        Spacer(Modifier.width(StatusGap))
        Text(secondary, style = KLineType.body, color = colors.inkMuted, maxLines = 1)
    }
}
