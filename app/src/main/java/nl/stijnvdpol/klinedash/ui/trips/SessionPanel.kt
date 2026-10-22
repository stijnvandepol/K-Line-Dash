package nl.stijnvdpol.klinedash.ui.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.service.RecordingState
import nl.stijnvdpol.klinedash.ui.HorizontalRule
import nl.stijnvdpol.klinedash.ui.PrimaryButton
import nl.stijnvdpol.klinedash.ui.RecordingHint
import nl.stijnvdpol.klinedash.ui.SecondaryButton
import nl.stijnvdpol.klinedash.ui.StatusDot
import nl.stijnvdpol.klinedash.ui.TextActionButton
import nl.stijnvdpol.klinedash.ui.ValueLook
import nl.stijnvdpol.klinedash.ui.VerticalRule
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private val StatusHeight = 80.dp
private val StatusDotTopInset = 10.dp
private val GridRowHeight = 96.dp
private val HeroRowHeight = 136.dp
private val SplitsHeight = 48.dp
private val ButtonGap = Spacing.grid

private val PrimaryLook = ValueLook(KLineType.primaryValue)
private val FieldLook = ValueLook(KLineType.fieldValue)

/** Widest text each field shows with every digit as "8": the numerals shrink to fit it, never wrap. */
private const val HERO_SAMPLE = "8:88.8"
private const val LAP_TIME_SAMPLE = "8:88.888"
private const val DELTA_SAMPLE = "+88.88"
private const val COUNT_SAMPLE = "888"
private const val TRIP_FIELD_SAMPLE = "8:88:88"

/**
 * The session at the top of the TRIPS page. Not recording: the two ways to start, and the track a
 * lap session would use. Recording a trip: its live statistics. A lap session: the lap timer and the
 * buttons for the start/finish and sector lines. The text of the state comes from [recordingHint]
 * when idle.
 */
@Composable
internal fun SessionPanel(vm: SessionViewModel, hint: RecordingHint, onChooseTrack: () -> Unit) {
    val recording by vm.recording.collectAsStateWithLifecycle()
    val isLapSession by vm.isLapSession.collectAsStateWithLifecycle()
    val error by vm.recordingError.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        when {
            recording !is RecordingState.Recording -> IdlePanel(vm, hint, onChooseTrack)
            isLapSession -> LapSessionPanel(vm, recording as RecordingState.Recording)
            else -> TripPanel(vm, recording as RecordingState.Recording)
        }
        error?.let { Note(it, KLineTheme.colors.danger) }
        message?.let { Note(it, KLineTheme.colors.ink) }
    }
}

@Composable
private fun Note(text: String, color: Color) {
    HorizontalRule()
    Text(
        text,
        style = KLineType.body,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
    )
}

/** Lamp and state like the Connection status block; under it [detail]. */
@Composable
private fun SessionStatus(isRecording: Boolean, title: String, detail: String) {
    val colors = KLineTheme.colors
    Row(
        Modifier.fillMaxWidth().height(StatusHeight)
            .padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
    ) {
        StatusDot(if (isRecording) colors.danger else colors.inkFaint, Modifier.padding(top = StatusDotTopInset))
        Spacer(Modifier.width(Spacing.fieldPaddingHorizontal))
        Column {
            Text(title, style = KLineType.title, color = colors.ink, maxLines = 1)
            Text(detail, style = KLineType.caption, color = colors.inkMuted, maxLines = 2)
        }
    }
    HorizontalRule()
}

@Composable
private fun IdlePanel(vm: SessionViewModel, hint: RecordingHint, onChooseTrack: () -> Unit) {
    val canStart by vm.canStart.collectAsStateWithLifecycle()
    val chosen by vm.chosenTrack.collectAsStateWithLifecycle()
    SessionStatus(false, stringResource(R.string.trips_state_idle), stringResource(hint.textRes()))
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid),
        verticalArrangement = Arrangement.spacedBy(ButtonGap),
    ) {
        PrimaryButton(stringResource(R.string.session_start_trip), vm::startTrip, Modifier.fillMaxWidth().height(Spacing.touchMin), enabled = canStart)
        SecondaryButton(
            stringResource(R.string.session_start_laps), vm::startLapSession,
            Modifier.fillMaxWidth().height(Spacing.touchMin), enabled = canStart,
        )
    }
    HorizontalRule()
    TextActionButton(
        stringResource(R.string.session_track, chosen?.name ?: stringResource(R.string.session_choose_track)),
        onChooseTrack,
        Modifier.fillMaxWidth().height(Spacing.touchMin),
    )
    HorizontalRule()
}

private fun RecordingHint.textRes(): Int = when (this) {
    RecordingHint.NOT_CONNECTED -> R.string.trips_hint_not_connected
    RecordingHint.WAITING_FOR_ENGINE -> R.string.trips_hint_waiting
    RecordingHint.PRESS_START -> R.string.trips_hint_press_start
}

@Composable
private fun TripPanel(vm: SessionViewModel, recording: RecordingState.Recording) {
    val live by vm.live.collectAsStateWithLifecycle()
    SessionStatus(true, stringResource(R.string.trips_state_recording), recording.fileName)
    val cells = liveStatCells(live.stats)
    cells.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth().height(GridRowHeight)) {
            pair.forEachIndexed { index, cell ->
                if (index > 0) VerticalRule()
                ValueField(cell.label, cell.unit, cell.value, FieldLook, TRIP_FIELD_SAMPLE, Modifier.weight(1f).fillMaxHeight())
            }
        }
        HorizontalRule()
    }
    ActionRow {
        SecondaryButton(stringResource(R.string.trips_stop), vm::stop, Modifier.weight(1f).height(Spacing.touchMin))
        SecondaryButton(stringResource(R.string.session_start_timing), vm::startLapTiming, Modifier.weight(1f).height(Spacing.touchMin))
    }
    HorizontalRule()
}

@Composable
private fun ActionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid),
        horizontalArrangement = Arrangement.spacedBy(ButtonGap),
        content = content,
    )
}

@Composable
private fun LapSessionPanel(vm: SessionViewModel, recording: RecordingState.Recording) {
    val live by vm.live.collectAsStateWithLifecycle()
    val track by vm.activeTrack.collectAsStateWithLifecycle()
    val gateBlock by vm.gateBlock.collectAsStateWithLifecycle()
    val hasLine = live.trackId != null
    SessionStatus(
        true,
        stringResource(R.string.session_lap_session),
        if (hasLine) track?.name ?: recording.fileName else stringResource(R.string.session_no_line),
    )
    if (hasLine) {
        LapTimer(lapPanel(live.lapTiming, track?.sectors?.size ?: 0))
    } else {
        SetStartFinish(vm, gateBlock)
    }
    ActionRow {
        if (hasLine) {
            SecondaryButton(
                stringResource(R.string.session_add_sector), vm::addSectorHere,
                Modifier.weight(1f).height(Spacing.touchMin), enabled = gateBlock == null,
            )
        }
        SecondaryButton(stringResource(R.string.trips_stop), vm::stop, Modifier.weight(1f).height(Spacing.touchMin))
    }
    if (hasLine && gateBlock != null) {
        Text(
            gateBlock.orEmpty(),
            style = KLineType.caption,
            color = KLineTheme.colors.inkMuted,
            modifier = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal).padding(bottom = Spacing.grid),
        )
    }
    HorizontalRule()
}

/** The way to the first line: what to do, the button, and why it cannot be pressed yet. */
@Composable
private fun SetStartFinish(vm: SessionViewModel, block: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical)) {
        Text(stringResource(R.string.session_set_hint), style = KLineType.body, color = KLineTheme.colors.inkMuted)
        Box(Modifier.padding(top = Spacing.grid)) {
            PrimaryButton(
                stringResource(R.string.session_set_start_finish), vm::setStartFinishHere,
                Modifier.fillMaxWidth().height(Spacing.touchMin), enabled = block == null,
            )
        }
        block?.let { Text(it, style = KLineType.caption, color = KLineTheme.colors.inkMuted, modifier = Modifier.padding(top = Spacing.grid)) }
    }
    HorizontalRule()
}

/** CURRENT LAP as the hero, LAST and BEST, DELTA to best (green ahead, red behind) and the lap count, then the sector splits. */
@Composable
private fun LapTimer(panel: LapPanel) {
    val colors = KLineTheme.colors
    ValueField(
        stringResource(R.string.session_current_lap), null, panel.current, PrimaryLook, HERO_SAMPLE,
        Modifier.fillMaxWidth().height(HeroRowHeight),
    )
    HorizontalRule()
    FieldRow(
        stringResource(R.string.session_last), panel.last, LAP_TIME_SAMPLE, colors.ink,
        stringResource(R.string.session_best), panel.best, LAP_TIME_SAMPLE, colors.ink,
    )
    FieldRow(
        stringResource(R.string.session_delta), panel.delta, DELTA_SAMPLE, panel.deltaTone.color(),
        stringResource(R.string.session_laps), "${panel.lapCount}", COUNT_SAMPLE, colors.ink,
    )
    if (panel.splits.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().height(SplitsHeight).padding(horizontal = Spacing.fieldPaddingHorizontal),
            horizontalArrangement = Arrangement.spacedBy(Spacing.fieldPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            panel.splits.forEach { split ->
                Text("${split.label}  ${split.time}", style = KLineType.body, color = colors.ink, maxLines = 1)
            }
        }
        HorizontalRule()
    }
}

@Composable
private fun FieldRow(
    leftLabel: String, leftValue: String, leftSample: String, leftColor: Color,
    rightLabel: String, rightValue: String, rightSample: String, rightColor: Color,
) {
    Row(Modifier.fillMaxWidth().height(GridRowHeight)) {
        ValueField(leftLabel, null, leftValue, FieldLook, leftSample, Modifier.weight(1f).fillMaxHeight(), leftColor)
        VerticalRule()
        ValueField(rightLabel, null, rightValue, FieldLook, rightSample, Modifier.weight(1f).fillMaxHeight(), rightColor)
    }
    HorizontalRule()
}

@Composable
private fun DeltaTone.color(): Color = when (this) {
    DeltaTone.FASTER -> KLineTheme.colors.ok
    DeltaTone.SLOWER -> KLineTheme.colors.danger
    DeltaTone.EVEN -> KLineTheme.colors.ink
}
