package nl.stijnvdpol.klinedash.ui.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.data.TrackPage
import nl.stijnvdpol.klinedash.ui.COOLANT_ID
import nl.stijnvdpol.klinedash.ui.DataField
import nl.stijnvdpol.klinedash.ui.FieldFrame
import nl.stijnvdpol.klinedash.ui.HorizontalRule
import nl.stijnvdpol.klinedash.ui.LiveState
import nl.stijnvdpol.klinedash.ui.RPM_ID
import nl.stijnvdpol.klinedash.ui.SPEED_ID
import nl.stijnvdpol.klinedash.ui.ShiftLight
import nl.stijnvdpol.klinedash.ui.TextActionButton
import nl.stijnvdpol.klinedash.ui.ValueLook
import nl.stijnvdpol.klinedash.ui.ValueTone
import nl.stijnvdpol.klinedash.ui.ValueText
import nl.stijnvdpol.klinedash.ui.VerticalRule
import nl.stijnvdpol.klinedash.ui.dashMetricsOf
import nl.stijnvdpol.klinedash.ui.LocalUnitSystem
import nl.stijnvdpol.klinedash.ui.slotSample
import nl.stijnvdpol.klinedash.ui.unitOf
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.LocalScreenProfile
import nl.stijnvdpol.klinedash.ui.theme.Spacing

/** The rpm takes two thirds of the width, also in the editor's sketch of the page. */
internal const val TRACK_RPM_COLUMN_WEIGHT = 2f
private const val SIDE_COLUMN_WEIGHT = 1f

/** A short screen gives the side column more width, because coolant and speed sit side by side there (see [SideColumn]). */
private const val COMPACT_RPM_COLUMN_WEIGHT = 1.5f
private val ShiftFrameThickness = 12.dp

/** The rpm numeral fills the left two thirds as far as its width allows: the tallest it can be on 720 dp. */
private const val RPM_MAX_GROWTH = 4f
private const val SIDE_MAX_GROWTH = 1.6f

private val RpmLook = ValueLook(KLineType.heroValue, RPM_MAX_GROWTH)
private val SideLook = ValueLook(KLineType.primaryValue, SIDE_MAX_GROWTH)
private val PeakLook = ValueLook(KLineType.fieldValue)

/**
 * The track page: RPM bar on top, the rpm as the big numeral on the left, coolant and speed on the
 * right with the peak hold and its reset cell below. From the shift point the bar and a 12 dp frame
 * flash in the danger colour. The frame has its own margin around the content, kept also while it
 * is dark, so it never covers a value and nothing moves when it flashes.
 */
@Composable
internal fun TrackPageContent(page: TrackPage, live: LiveState, peaks: State<Peaks>, onResetPeaks: () -> Unit) {
    val shiftLight = remember(page.shiftRpm) { ShiftLight(page.shiftRpm) }
    val danger = KLineTheme.colors.danger
    Box(
        Modifier.fillMaxSize().drawWithContent {
            drawContent()
            if (shiftLight.isLit) drawFrame(danger, ShiftFrameThickness.toPx())
        },
    ) {
        Column(Modifier.fillMaxSize().padding(ShiftFrameThickness)) {
            RpmBarRow(live, shiftLight)
            HorizontalRule()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                val rpmWeight = if (LocalScreenProfile.current.isCompact) COMPACT_RPM_COLUMN_WEIGHT else TRACK_RPM_COLUMN_WEIGHT
                DataField(RPM_ID, RpmLook, live, Modifier.weight(rpmWeight).fillMaxHeight())
                VerticalRule()
                SideColumn(live, peaks, onResetPeaks, Modifier.weight(SIDE_COLUMN_WEIGHT).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun SideColumn(live: LiveState, peaks: State<Peaks>, onResetPeaks: () -> Unit, modifier: Modifier) {
    val speedUnit = unitOf(checkNotNull(Signals.byId(SPEED_ID)), LocalUnitSystem.current)
    Column(modifier) {
        if (LocalScreenProfile.current.isCompact) {
            // Height is the scarce thing on a phone: coolant and speed share one row and so get all of it.
            Row(Modifier.weight(1f).fillMaxWidth()) {
                DataField(COOLANT_ID, SideLook, live, Modifier.weight(1f).fillMaxHeight())
                VerticalRule()
                DataField(SPEED_ID, SideLook, live, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            DataField(COOLANT_ID, SideLook, live, Modifier.weight(1f).fillMaxWidth())
            HorizontalRule()
            DataField(SPEED_ID, SideLook, live, Modifier.weight(1f).fillMaxWidth())
        }
        HorizontalRule()
        Row(Modifier.height(dashMetricsOf(LocalScreenProfile.current.tier).smallFieldRowHeight).fillMaxWidth()) {
            PeakField(stringResource(R.string.track_max_rpm), RPM_ID, peaks, { it.maxRpm }, Modifier.weight(1f).fillMaxHeight())
            VerticalRule()
            PeakField(stringResource(R.string.track_max_speed, speedUnit), SPEED_ID, peaks, { it.maxSpeed }, Modifier.weight(1f).fillMaxHeight())
        }
        HorizontalRule()
        TextActionButton(stringResource(R.string.track_reset_peaks), onResetPeaks, Modifier.fillMaxWidth().height(Spacing.touchMin))
    }
}

/** A peak value in the look of a small field; "--" until a reading has come in since the reset. */
@Composable
private fun PeakField(label: String, signalId: String, peaks: State<Peaks>, select: (Peaks) -> Double?, modifier: Modifier) {
    val signal = remember(signalId) { checkNotNull(Signals.byId(signalId)) }
    val reading = remember(peaks, signalId) { derivedStateOf { select(peaks.value)?.let { Reading(signalId, it, 0L) } } }
    val never = remember { mutableStateOf(false) }
    val normal = remember { mutableStateOf(ValueTone.NORMAL) }
    FieldFrame(label.uppercase(), unit = null, look = PeakLook, slotSample = slotSample(signal, LocalUnitSystem.current), modifier = modifier) { style ->
        ValueText(signal, reading, never, normal, style)
    }
}

private fun DrawScope.drawFrame(color: Color, thickness: Float) {
    drawRect(color, Offset.Zero, Size(size.width, thickness))
    drawRect(color, Offset(0f, size.height - thickness), Size(size.width, thickness))
    drawRect(color, Offset(0f, thickness), Size(thickness, size.height - 2 * thickness))
    drawRect(color, Offset(size.width - thickness, thickness), Size(thickness, size.height - 2 * thickness))
}
