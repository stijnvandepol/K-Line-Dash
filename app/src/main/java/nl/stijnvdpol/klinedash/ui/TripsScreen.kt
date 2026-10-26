package nl.stijnvdpol.klinedash.ui

import android.content.ActivityNotFoundException
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.data.TripSettings
import nl.stijnvdpol.klinedash.service.CopyProgress
import nl.stijnvdpol.klinedash.service.ExportState
import nl.stijnvdpol.klinedash.service.RecordingState
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.LocalScreenProfile
import nl.stijnvdpol.klinedash.ui.theme.Spacing
import nl.stijnvdpol.klinedash.ui.trips.ChooseTrackDialog
import nl.stijnvdpol.klinedash.ui.trips.SessionActions
import nl.stijnvdpol.klinedash.ui.trips.SessionPanel
import nl.stijnvdpol.klinedash.ui.trips.SessionViewModel
import nl.stijnvdpol.klinedash.ui.trips.TripDetailScreen

private const val TAG = "TripsScreen"
private val SessionColumnWidth = 480.dp
private val SessionColumnMinWidth = 340.dp
private const val SESSION_COLUMN_SHARE = 0.42f

/**
 * TRIPS: the session (start a trip or a lap session, live statistics and lap timer), the automatic
 * recording switches and the export on the left, the trip list on the right, a line for notices along
 * the bottom. A tap on a trip opens its detail page. Delete asks once; copying to USB or SD uses the
 * system folder picker, so no permission.
 */
@Composable
fun TripsScreen() {
    var openTripId by rememberSaveable { mutableStateOf<Long?>(null) }
    val tripId = openTripId
    if (tripId != null) {
        TripDetailScreen(tripId, onBack = { openTripId = null })
    } else {
        TripsOverview(onOpenTrip = { openTripId = it })
    }
}

@Composable
private fun TripsOverview(onOpenTrip: (Long) -> Unit) {
    val vm = graphViewModel { TripsViewModel.forGraph(it) }
    val session = graphViewModel { SessionViewModel.forGraph(it) }
    val recording = vm.recording.collectAsStateWithLifecycle()
    val trips by vm.trips.collectAsStateWithLifecycle()
    val trackNames by vm.trackNames.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<TripRow?>(null) }
    var isChoosingTrack by remember { mutableStateOf(false) }
    // Only the file name decides which row is "Recording": the sample count changes every second.
    val recordingFileName by remember(recording) { derivedStateOf { (recording.value as? RecordingState.Recording)?.fileName } }
    val units = LocalUnitSystem.current
    val rows = remember(trips, recordingFileName, trackNames, units) { trips?.let { tripRows(it, recordingFileName, units, trackNames) } }

    DisposableEffect(vm) { onDispose(vm::clearCopyResult) }

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // 480 dp from a 1140 dp canvas up; a narrower one gives the list more than half.
            val sessionWidth = sideColumnWidth(maxWidth, SESSION_COLUMN_SHARE, SessionColumnMinWidth, SessionColumnWidth)
            Row(Modifier.fillMaxSize()) {
                SessionColumn(vm, session, settings, { isChoosingTrack = true }, Modifier.width(sessionWidth).fillMaxHeight())
                VerticalRule()
                TripList(rows, settings?.autoRecord != false, onOpen = onOpenTrip, onDelete = { pendingDelete = it }, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        }
        notice?.let { NoticeLine(it, KLineTheme.colors.ink) }
    }
    pendingDelete?.let { row ->
        DeleteTripDialog(row.start, onCancel = { pendingDelete = null }, onDelete = {
            vm.delete(row.id)
            pendingDelete = null
        })
    }
    if (isChoosingTrack) ChooseTrackDialog(session, onDismiss = { isChoosingTrack = false })
}

/** The session, the automatic recording switches, then the export block. Scrolls when the head unit's screen is shorter than the content. */
@Composable
private fun SessionColumn(vm: TripsViewModel, session: SessionViewModel, settings: TripSettings?, onChooseTrack: () -> Unit, modifier: Modifier) {
    val canStart by session.canStart.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    val copy by vm.copy.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    // The export buttons are at the bottom: when they start to report something (address and PIN, a
    // copy result) it is scrolled into view; it is not when the page opens, so the session stays on top.
    val hasReport = export is ExportState.Running || copy != null
    var hadReport by remember { mutableStateOf(hasReport) }
    LaunchedEffect(hasReport) {
        if (hasReport && !hadReport) {
            withFrameNanos { }
            scroll.animateScrollTo(scroll.maxValue)
        }
        hadReport = hasReport
    }
    // On a phone the buttons of a running session stay at the bottom, out of the scroll.
    val pinnedActions = areSessionActionsPinned(LocalScreenProfile.current)
    Column(modifier) {
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            SessionPanel(session, recordingHint(canStart, settings?.autoRecord != false), onChooseTrack, pinnedActions)
            SectionTitle(stringResource(R.string.trips_section_auto))
            SwitchRow(stringResource(R.string.trips_auto_record), settings?.autoRecord == true, vm::setAutoRecord, Modifier.height(Spacing.touchMin))
            HorizontalRule()
            SwitchRow(
                stringResource(R.string.trips_auto_moving), settings?.autoStartWhenMoving == true, vm::setAutoStartWhenMoving,
                Modifier.height(Spacing.touchMin), enabled = settings?.autoRecord == true,
            )
            HorizontalRule()
            KeepTripsSection(settings, vm)
            ExportSection(vm, export, copy)
        }
        if (pinnedActions) SessionActions(session, isPinned = true)
    }
}

/** How many trips are kept; the older ones are deleted when a trip ends. Lap sessions are never counted. */
@Composable
private fun KeepTripsSection(settings: TripSettings?, vm: TripsViewModel) {
    // Not shown with a made-up value while the settings are being read.
    val keepTrips = settings?.keepTrips ?: return
    SectionTitle(stringResource(R.string.trips_section_keep))
    HintText(stringResource(R.string.trips_keep_hint))
    StepperRow(
        label = stringResource(R.string.trips_keep_label),
        value = keepTrips.toString(),
        unit = null,
        words = StepWords.LOWER_RAISE,
        onStep = vm::stepKeepTrips,
        canStep = { canStepKeepTrips(keepTrips, it) },
    )
    HorizontalRule()
}

@Composable
internal fun ErrorText(text: String) {
    Text(
        text,
        style = KLineType.body,
        color = KLineTheme.colors.danger,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
    )
}

/** Copy to USB or SD and the Wi-Fi export: two outlined buttons, then what each one reports. */
@Composable
private fun ExportSection(vm: TripsViewModel, export: ExportState, copy: CopyProgress?) {
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.copyTo(uri)
    }
    SectionTitle(stringResource(R.string.trips_section_export))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal),
        horizontalArrangement = Arrangement.spacedBy(Spacing.fieldPaddingHorizontal),
    ) {
        SecondaryButton(
            stringResource(R.string.trips_copy),
            onClick = {
                try {
                    folderPicker.launch(null)
                } catch (e: ActivityNotFoundException) {
                    // Many head units ship without a DocumentsUI app, so there is nothing to pick a folder with.
                    Log.w(TAG, "No folder picker on this device", e)
                    vm.folderPickerMissing()
                }
            },
            modifier = Modifier.weight(1f),
            enabled = copy?.isFinished != false,
        )
        SecondaryButton(
            stringResource(if (export is ExportState.Running) R.string.trips_wifi_stop else R.string.trips_wifi_start),
            onClick = { if (export is ExportState.Running) vm.stopExport() else vm.startExport() },
            modifier = Modifier.weight(1f),
        )
    }
    copy?.let { CopyStatusBlock(it) }
    when (val state = export) {
        ExportState.Off -> Unit
        is ExportState.Running -> WifiRunning(state)
        is ExportState.Failed -> ErrorText(state.reason)
    }
}

@Composable
private fun CopyStatusBlock(progress: CopyProgress) {
    val colors = KLineTheme.colors
    val status = copyStatus(progress)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
    ) {
        Text(status.summary, style = KLineType.body, color = if (status.isError) colors.danger else colors.ink)
        status.detail?.let { Text(it, style = KLineType.caption, color = colors.inkMuted) }
    }
}

/** Where to open the export and the PIN to type there; the PIN is a numeral like every other value. */
@Composable
private fun WifiRunning(state: ExportState.Running) {
    val colors = KLineTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.fieldPaddingHorizontal)) {
            Column(Modifier.weight(1f)) {
                state.addresses.forEach { address -> Text(address, style = KLineType.title, color = colors.ink, maxLines = 1) }
            }
            Column(horizontalAlignment = Alignment.End) {
                FieldCaption(stringResource(R.string.trips_label_pin).uppercase())
                Text(
                    state.pin,
                    style = KLineType.fieldValue,
                    color = colors.ink,
                    maxLines = 1,
                    modifier = Modifier.padding(top = Spacing.grid).capHeightBox(KLineType.fieldValue.fontSize),
                )
            }
        }
        Text(stringResource(R.string.trips_wifi_idle_note), style = KLineType.caption, color = colors.inkMuted)
    }
}
