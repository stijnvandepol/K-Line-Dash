package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private val InfoRowHeight = 56.dp
private val CellWidth = 128.dp
private val InfoUnitWidth = 64.dp

/** The widest value of the GPS rows with every digit as "8", so the values share one right edge. */
private const val INFO_VALUE_SAMPLE = "Not allowed"

/**
 * SETUP > GPS & Tracks, one scrolling column: the state of the GPS (with "Allow location" when the
 * permission is missing), the simulated circuit (only with the simulator connection) and the saved
 * tracks with rename, delete and the choice of the active one. [onAllowLocation] asks the system for
 * the location permission; the activity owns that dialog.
 */
@Composable
internal fun GpsTracksScreen(onAllowLocation: () -> Unit) {
    val vm = graphViewModel { GpsTracksViewModel.forGraph(it) }
    val status by vm.status.collectAsStateWithLifecycle()
    val isSimulatorConnection by vm.isSimulatorConnection.collectAsStateWithLifecycle()
    val useSimulated by vm.useSimulated.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val canRename by vm.canRename.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<TrackRowModel?>(null) }
    var deleting by remember { mutableStateOf<TrackRowModel?>(null) }
    val info = gpsInfo(status)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionTitle(stringResource(R.string.gps_title))
        InfoRow(stringResource(R.string.gps_state), info.state, null)
        InfoRow(stringResource(R.string.gps_satellites), info.satellites, null)
        InfoRow(stringResource(R.string.gps_accuracy), info.accuracyM, "m")
        InfoRow(stringResource(R.string.gps_rate), info.rateHz, "Hz")
        info.hint?.let { Hint(it) }
        if (status == nl.stijnvdpol.klinedash.service.GpsStatus.NoPermission) {
            PrimaryButton(
                stringResource(R.string.gps_allow), onAllowLocation,
                Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid).height(Spacing.touchMin),
            )
        }
        HorizontalRule()
        if (isSimulatorConnection) {
            SwitchRow(stringResource(R.string.gps_simulated), useSimulated, vm::setUseSimulated, Modifier.height(Spacing.touchMin))
            Hint(stringResource(R.string.gps_simulated_hint))
            HorizontalRule()
        }
        SectionTitle(stringResource(R.string.gps_tracks_title))
        TrackList(tracks, canRename, vm, onRename = { renaming = it }, onDelete = { deleting = it })
    }
    renaming?.let { track ->
        RenameTrackDialog(track.name, onCancel = { renaming = null }, onSave = {
            vm.rename(track.id, it)
            renaming = null
        })
    }
    deleting?.let { track ->
        DeleteTrackDialog(track.name, onCancel = { deleting = null }, onDelete = {
            vm.delete(track.id)
            deleting = null
        })
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = KLineType.caption,
        color = KLineTheme.colors.inkMuted,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid),
    )
}

/** Name on the left, value right-aligned in a fixed slot, unit after it. */
@Composable
private fun InfoRow(name: String, value: String, unit: String?) {
    val colors = KLineTheme.colors
    val valueWidth = rememberTextWidth(INFO_VALUE_SAMPLE, KLineType.rowValue)
    Row(
        Modifier.fillMaxWidth().height(InfoRowHeight).padding(horizontal = Spacing.fieldPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = KLineType.body, color = colors.ink, modifier = Modifier.weight(1f))
        Text(
            value,
            style = KLineType.rowValue,
            color = if (value == MISSING_VALUE) colors.inkFaint else colors.ink,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(valueWidth),
        )
        Text(unit.orEmpty(), style = KLineType.caption, color = colors.inkMuted, modifier = Modifier.width(InfoUnitWidth).padding(start = Spacing.grid))
    }
    HorizontalRule()
}

@Composable
private fun TrackList(tracks: List<TrackRowModel>?, canRename: Boolean, vm: GpsTracksViewModel, onRename: (TrackRowModel) -> Unit, onDelete: (TrackRowModel) -> Unit) {
    when {
        tracks == null -> Unit
        tracks.isEmpty() -> Hint(stringResource(R.string.gps_no_tracks))
        else -> {
            Hint(stringResource(if (canRename) R.string.gps_tracks_hint else R.string.gps_tracks_hint_moving))
            HorizontalRule()
            tracks.forEach { track -> TrackRow(track, canRename, vm, onRename, onDelete) }
        }
    }
}

/** Name and what the track holds, then three cells: use (or active), rename, delete. */
@Composable
private fun TrackRow(track: TrackRowModel, canRename: Boolean, vm: GpsTracksViewModel, onRename: (TrackRowModel) -> Unit, onDelete: (TrackRowModel) -> Unit) {
    val colors = KLineTheme.colors
    Row(Modifier.fillMaxWidth().height(Spacing.touchMin)) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = Spacing.fieldPaddingHorizontal), verticalArrangement = Arrangement.Center) {
            Text(track.name, style = KLineType.body, color = colors.ink, maxLines = 1)
            Text(track.detail, style = KLineType.caption, color = colors.inkMuted, maxLines = 1)
        }
        VerticalRule()
        if (track.isActive) {
            TextActionButton(stringResource(R.string.gps_track_active), { vm.setActive(null) }, Modifier.width(CellWidth).fillMaxHeight())
        } else {
            TextActionButton(stringResource(R.string.gps_track_use), { vm.setActive(track.id) }, Modifier.width(CellWidth).fillMaxHeight())
        }
        VerticalRule()
        TextActionButton(stringResource(R.string.gps_track_rename), { onRename(track) }, Modifier.width(CellWidth).fillMaxHeight(), enabled = canRename)
        VerticalRule()
        DangerTextButton(stringResource(R.string.trips_delete), { onDelete(track) }, Modifier.width(CellWidth).fillMaxHeight())
    }
    HorizontalRule()
}

/** The new name in a field; a problem with it shows above the field, because below it is off screen while the keyboard is open. */
@Composable
private fun RenameTrackDialog(current: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    val problem = trackNameProblem(name)
    val focus = rememberInitialFocus()
    RaceDialog(
        title = stringResource(R.string.gps_rename_title),
        onDismiss = onCancel,
        actions = {
            SecondaryButton(stringResource(R.string.btn_cancel), onCancel, Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.gps_rename_save), { onSave(name.trim()) }, Modifier.weight(1f), enabled = problem == null)
        },
    ) {
        HorizontalRule()
        Column(Modifier.padding(Spacing.fieldPaddingHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.grid)) {
            problem?.let { Text(it, style = KLineType.caption, color = KLineTheme.colors.danger) }
            FormTextField(name, { name = it }, Modifier.fillMaxWidth().focusRequester(focus))
        }
    }
}

@Composable
private fun DeleteTrackDialog(name: String, onCancel: () -> Unit, onDelete: () -> Unit) {
    val focus = rememberInitialFocus()
    RaceDialog(
        title = stringResource(R.string.gps_delete_title),
        onDismiss = onCancel,
        accent = KLineTheme.colors.danger,
        actions = {
            PrimaryButton(stringResource(R.string.btn_cancel), onCancel, Modifier.weight(1f).focusRequester(focus))
            DangerButton(stringResource(R.string.trips_delete), onDelete, Modifier.weight(1f))
        },
    ) {
        HorizontalRule()
        DialogText(deleteTrackQuestion(name))
    }
}
