package nl.stijnvdpol.klinedash.ui.trips

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.data.Track
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.service.TripDetail
import nl.stijnvdpol.klinedash.ui.DangerTextButton
import nl.stijnvdpol.klinedash.ui.DeleteTripDialog
import nl.stijnvdpol.klinedash.ui.EmptyState
import nl.stijnvdpol.klinedash.ui.HorizontalRule
import nl.stijnvdpol.klinedash.ui.TextActionButton
import nl.stijnvdpol.klinedash.ui.VerticalRule
import nl.stijnvdpol.klinedash.ui.formatTripStart
import nl.stijnvdpol.klinedash.ui.graphViewModel
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private val BackCellWidth = 120.dp
private val ExportCellWidth = 200.dp
private val DeleteCellWidth = 140.dp
private val RouteWidth = 500.dp

/**
 * The trip detail page: a strip with the way back, the date and track, "Export for PC" and "Delete";
 * the route on the left; the statistics, the lap table and the graph of the selected lap on the
 * right. [onBack] leaves the page; it also runs when the trip disappears (deleted here).
 */
@Composable
internal fun TripDetailScreen(tripId: Long, onBack: () -> Unit) {
    val vm = graphViewModel { TripDetailViewModel.forGraph(it) }
    LaunchedEffect(tripId) { vm.open(tripId) }
    BackHandler(onBack = onBack)
    val trips by vm.trips.collectAsStateWithLifecycle()
    val trip = trips?.firstOrNull { it.id == tripId }
    // The list has loaded and no longer holds the trip: it was deleted, here or by the export server's owner.
    LaunchedEffect(trips) { if (trips != null && trip == null) onBack() }
    val state by vm.detail.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    val deleteError by vm.deleteError.collectAsStateWithLifecycle()
    var isConfirmingDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopStrip(trip, (state as? DetailState.Loaded)?.detail?.takeIf { it.tripId == tripId }?.track, onBack, vm::toggleExportChoices) { isConfirmingDelete = true }
        HorizontalRule()
        ExportPanel(export, vm::export)
        deleteError?.let { Note(it) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val current = state) {
                is DetailState.Loaded -> if (current.detail.tripId == tripId) DetailBody(vm, current.detail) else EmptyState(stringResource(R.string.detail_loading))
                is DetailState.Failed -> EmptyState(current.reason)
                DetailState.Loading -> EmptyState(stringResource(R.string.detail_loading))
            }
        }
    }
    if (isConfirmingDelete && trip != null) {
        DeleteTripDialog(formatTripStart(trip.startMs), onCancel = { isConfirmingDelete = false }, onDelete = {
            isConfirmingDelete = false
            vm.delete()
        })
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = KLineType.body,
        color = KLineTheme.colors.danger,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
    )
    HorizontalRule()
}

/** Back cell, date and track, then the two actions as cells separated by rules. */
@Composable
private fun TopStrip(trip: Trip?, track: Track?, onBack: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    val colors = KLineTheme.colors
    Row(Modifier.fillMaxWidth().height(Spacing.touchMin)) {
        TextActionButton(stringResource(R.string.detail_back), onBack, Modifier.width(BackCellWidth).fillMaxHeight())
        VerticalRule()
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = Spacing.fieldPaddingHorizontal), verticalArrangement = Arrangement.Center) {
            Text(trip?.let { formatTripStart(it.startMs) }.orEmpty(), style = KLineType.title, color = colors.ink, maxLines = 1)
            track?.let { Text(it.name, style = KLineType.caption, color = colors.inkMuted, maxLines = 1) }
        }
        VerticalRule()
        TextActionButton(stringResource(R.string.detail_export), onExport, Modifier.width(ExportCellWidth).fillMaxHeight())
        VerticalRule()
        DangerTextButton(stringResource(R.string.trips_delete), onDelete, Modifier.width(DeleteCellWidth).fillMaxHeight())
    }
}

/** The choice of formats as 72 dp cells, and under it what the export wrote. */
@Composable
private fun ExportPanel(state: ExportUiState, onExport: (ExportChoice) -> Unit) {
    if (state == ExportUiState.Closed) return
    val colors = KLineTheme.colors
    Row(Modifier.fillMaxWidth().height(Spacing.touchMin)) {
        ExportChoice.entries.forEachIndexed { index, choice ->
            if (index > 0) VerticalRule()
            TextActionButton(choice.label, { onExport(choice) }, Modifier.weight(1f).fillMaxHeight(), enabled = state != ExportUiState.Working)
        }
    }
    HorizontalRule()
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical)) {
        when (state) {
            ExportUiState.Working -> Text(stringResource(R.string.detail_exporting), style = KLineType.body, color = colors.inkMuted)
            is ExportUiState.Failed -> Text(state.reason, style = KLineType.body, color = colors.danger)
            is ExportUiState.Done -> ExportResultLines(state.outcome as? ExportOutcome.Written)
            ExportUiState.Choosing -> Text(stringResource(R.string.detail_export_hint), style = KLineType.body, color = colors.inkMuted)
            ExportUiState.Closed -> Unit
        }
    }
    HorizontalRule()
}

@Composable
private fun ExportResultLines(written: ExportOutcome.Written?) {
    val colors = KLineTheme.colors
    written ?: return
    written.fileNames.forEach { Text(it, style = KLineType.body, color = colors.ink) }
    written.notes.forEach { Text(it, style = KLineType.caption, color = colors.inkMuted) }
    Text(stringResource(R.string.detail_export_where), style = KLineType.caption, color = colors.inkMuted)
}

@Composable
private fun DetailBody(vm: TripDetailViewModel, detail: TripDetail) {
    val selected by vm.selectedLap.collectAsStateWithLifecycle()
    val trace by vm.lapTrace.collectAsStateWithLifecycle()
    val lapRoute = (trace as? LapTraceState.Ready)?.takeIf { it.lapNumber == selected }?.trace?.route
    Row(Modifier.fillMaxSize()) {
        if (detail.hasGps) {
            RouteView(
                route = detail.route, lap = lapRoute, track = detail.track,
                description = stringResource(R.string.detail_route_description),
                modifier = Modifier.width(RouteWidth).fillMaxHeight(),
            )
        } else {
            Column(Modifier.width(RouteWidth).fillMaxHeight()) { EmptyState(stringResource(R.string.detail_no_gps)) }
        }
        VerticalRule()
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            StatsTable(statCells(detail.stats))
            LapSection(detail, selected, trace, vm::selectLap)
        }
    }
}

/** The lap table with the sectors and graph of the selected lap, or the reason there are no laps. */
@Composable
private fun LapSection(detail: TripDetail, selected: Int?, trace: LapTraceState, onSelect: (Int) -> Unit) {
    if (detail.laps.isEmpty()) {
        Text(
            noLapsText(detail.track),
            style = KLineType.body,
            color = KLineTheme.colors.inkMuted,
            modifier = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
        )
        return
    }
    val rows = remember(detail) { lapRows(detail.laps) }
    LapTable(rows, selected, onSelect)
    rows.firstOrNull { it.number == selected }?.takeIf { it.sectors.isNotEmpty() }?.let { SectorTimes(it.number, it.sectors) }
    when (trace) {
        is LapTraceState.Ready -> LapTraceGraph(trace.trace, Modifier.fillMaxWidth())
        LapTraceState.Unavailable -> Text(
            stringResource(R.string.detail_lap_unavailable),
            style = KLineType.body,
            color = KLineTheme.colors.danger,
            modifier = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
        )
        LapTraceState.Loading, LapTraceState.None -> Unit
    }
}

/** Why the trip has no laps: no track was set, or none was completed on it. */
internal fun noLapsText(track: Track?): String =
    if (track == null) "No track was set for this trip, so there are no laps." else "No lap was completed on ${track.name}."
