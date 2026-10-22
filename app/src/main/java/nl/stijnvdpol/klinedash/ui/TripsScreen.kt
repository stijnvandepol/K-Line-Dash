package nl.stijnvdpol.klinedash.ui

import android.content.ActivityNotFoundException
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.service.CopyProgress
import nl.stijnvdpol.klinedash.service.ExportState
import nl.stijnvdpol.klinedash.service.RecordingState
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private const val TAG = "TripsScreen"
private val RecordingColumnWidth = 480.dp
private val StatusHeight = 80.dp
private val NumbersHeight = 96.dp
private val StatusDotTopInset = 10.dp

/**
 * TRIPS: recording and export on the left, the trip list on the right, a line for notices along the
 * bottom. Delete asks once; copying to USB or SD uses the system folder picker, so no permission.
 */
@Composable
fun TripsScreen() {
    val vm = graphViewModel { TripsViewModel.forGraph(it) }
    val recording = vm.recording.collectAsStateWithLifecycle()
    val trips by vm.trips.collectAsStateWithLifecycle()
    val isAutoRecord by vm.isAutoRecord.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<TripRow?>(null) }
    // Only the file name decides which row is "Recording": the sample count changes every second.
    val recordingFileName by remember(recording) { derivedStateOf { (recording.value as? RecordingState.Recording)?.fileName } }
    val rows = remember(trips, recordingFileName) { trips?.let { tripRows(it, recordingFileName) } }

    DisposableEffect(vm) { onDispose(vm::clearCopyResult) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            RecordingColumn(vm, recording, isAutoRecord, Modifier.width(RecordingColumnWidth).fillMaxHeight())
            VerticalRule()
            TripList(rows, isAutoRecord != false, onDelete = { pendingDelete = it }, modifier = Modifier.weight(1f).fillMaxHeight())
        }
        notice?.let { NoticeBar(it) }
    }
    pendingDelete?.let { row ->
        DeleteTripDialog(row, onCancel = { pendingDelete = null }, onDelete = {
            vm.delete(row.id)
            pendingDelete = null
        })
    }
}

@Composable
private fun NoticeBar(text: String) {
    HorizontalRule()
    Text(
        text,
        style = KLineType.body,
        color = KLineTheme.colors.ink,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
    )
}

/** The question before a trip is removed. Cancel has the focus, so a D-pad press cannot delete by accident. */
@Composable
private fun DeleteTripDialog(row: TripRow, onCancel: () -> Unit, onDelete: () -> Unit) {
    val focus = rememberInitialFocus()
    RaceDialog(
        title = stringResource(R.string.trips_delete_title),
        onDismiss = onCancel,
        accent = KLineTheme.colors.danger,
        actions = {
            PrimaryButton(stringResource(R.string.btn_cancel), onCancel, Modifier.weight(1f).focusRequester(focus))
            DangerButton(stringResource(R.string.trips_delete), onDelete, Modifier.weight(1f))
        },
    ) {
        HorizontalRule()
        DialogText(deleteQuestion(row))
    }
}

/** Recording state and controls, then the export block. Scrolls when the head unit's screen is shorter than the content. */
@Composable
private fun RecordingColumn(vm: TripsViewModel, recording: State<RecordingState>, isAutoRecord: Boolean?, modifier: Modifier) {
    val isConnected by vm.isConnected.collectAsStateWithLifecycle()
    val error by vm.recordingError.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    val copy by vm.copy.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    // The export buttons are at the bottom: when they start to report something (address and PIN, a
    // copy result) it is scrolled into view; it is not when the page opens, so the state stays on top.
    val hasReport = export is ExportState.Running || copy != null
    var hadReport by remember { mutableStateOf(hasReport) }
    LaunchedEffect(hasReport) {
        if (hasReport && !hadReport) {
            withFrameNanos { }
            scroll.animateScrollTo(scroll.maxValue)
        }
        hadReport = hasReport
    }
    Column(modifier.verticalScroll(scroll)) {
        RecordingStatus(recording, recordingHint(isConnected, isAutoRecord != false), Modifier.fillMaxWidth().height(StatusHeight))
        HorizontalRule()
        RecordingNumbers(recording, Modifier.fillMaxWidth().height(NumbersHeight))
        HorizontalRule()
        RecordAction(vm, isRecording = recording.value is RecordingState.Recording, canRecord = isConnected)
        HorizontalRule()
        error?.let {
            ErrorText(it)
            HorizontalRule()
        }
        SwitchRow(stringResource(R.string.trips_auto_record), isAutoRecord == true, vm::setAutoRecord, Modifier.height(Spacing.touchMin))
        HorizontalRule()
        ExportSection(vm, export, copy)
    }
}

/** Record (white) or Stop (outline): the one action of the panel, a 72 dp button with the side margin of the Export buttons. */
@Composable
private fun RecordAction(vm: TripsViewModel, isRecording: Boolean, canRecord: Boolean) {
    val button = Modifier.fillMaxWidth().height(Spacing.touchMin)
    Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid)) {
        if (isRecording) {
            SecondaryButton(stringResource(R.string.trips_stop), vm::stopRecording, button)
        } else {
            PrimaryButton(stringResource(R.string.trips_record), vm::startRecording, button, enabled = canRecord)
        }
    }
}

/** Lamp and state like the Connection status block; under it the file name, or what to do next. */
@Composable
private fun RecordingStatus(recording: State<RecordingState>, hint: RecordingHint, modifier: Modifier) {
    val colors = KLineTheme.colors
    val state = recording.value
    val isRecording = state is RecordingState.Recording
    Row(modifier.padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical)) {
        StatusDot(if (isRecording) colors.danger else colors.inkFaint, Modifier.padding(top = StatusDotTopInset))
        Spacer(Modifier.width(Spacing.fieldPaddingHorizontal))
        Column {
            Text(
                stringResource(if (isRecording) R.string.trips_state_recording else R.string.trips_state_idle),
                style = KLineType.title, color = colors.ink, maxLines = 1,
            )
            Text(
                text = if (state is RecordingState.Recording) state.fileName else stringResource(hint.textRes()),
                style = KLineType.caption, color = colors.inkMuted, maxLines = 2,
            )
        }
    }
}

private fun RecordingHint.textRes(): Int = when (this) {
    RecordingHint.NOT_CONNECTED -> R.string.trips_hint_not_connected
    RecordingHint.WAITING_FOR_ENGINE -> R.string.trips_hint_waiting
    RecordingHint.PRESS_RECORD -> R.string.trips_hint_press_record
}

/** Elapsed time and samples as two fields like the dashboard's; "--" while nothing records. */
@Composable
private fun RecordingNumbers(recording: State<RecordingState>, modifier: Modifier) {
    val colors = KLineTheme.colors
    val nowMs by rememberNowMs()
    val state = recording.value
    Row(modifier) {
        StripField(
            label = stringResource(R.string.trips_label_elapsed),
            value = (state as? RecordingState.Recording)?.let { formatDuration(elapsedMs(it.startMs, nowMs)) },
            valueColor = colors.ink,
            modifier = Modifier.weight(1f),
        )
        VerticalRule()
        StripField(
            label = stringResource(R.string.trips_label_samples),
            value = (state as? RecordingState.Recording)?.samples?.toString(),
            valueColor = colors.ink,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ErrorText(text: String) {
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
