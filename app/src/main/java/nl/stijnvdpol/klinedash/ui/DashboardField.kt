package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.obd.PollStats
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.obd.Signal
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private val LabelToValueGap = 8.dp

/** What a field loses of its inner height above the value: the label line and the gap under it. */
internal fun Density.labelAndGapHeight(): Dp = KLineType.fieldLabel.fontSize.toDp() * CAP_HEIGHT_EM + LabelToValueGap

/** Slot width for a signal this version does not know: wide enough for a few digits. */
private const val UNKNOWN_SIGNAL_SAMPLE = "888"

/**
 * What the pages read from the poller, as States so that only the small composables that show a
 * value recompose for a new reading (see LiveValues.kt).
 */
@Stable
internal class LiveState(
    val readings: State<Map<String, Reading>>,
    val stats: State<PollStats>,
    val nowMs: State<Long>,
    val toneContext: State<ToneContext>,
)

/**
 * The size of a field's numerals: [style] is the design size, [maxGrowth] how far past it the value
 * may grow to fill the field (1 = it only shrinks, so a smaller head unit scales it down).
 */
internal class ValueLook(val style: TextStyle, val maxGrowth: Float = 1f)

/** The labels of the short race-display vocabulary; signals without one use their short name. */
private val FieldLabelRes = mapOf(
    RPM_ID to R.string.field_rpm,
    SPEED_ID to R.string.field_speed,
    COOLANT_ID to R.string.field_coolant,
    INTAKE_ID to R.string.field_intake,
    THROTTLE_ID to R.string.field_throttle,
    LOAD_ID to R.string.field_load,
    TIMING_ID to R.string.field_timing,
    BATTERY_ID to R.string.field_battery,
    MAF_ID to R.string.field_maf,
)

/** The caps label of a signal's field. */
@Composable
internal fun fieldLabel(signal: Signal): String =
    (FieldLabelRes[signal.id]?.let { stringResource(it) } ?: signal.shortName).uppercase()

/** Label or unit of a field: muted capitals, boxed to their cap height so they sit 12 dp from the rule. */
@Composable
internal fun FieldCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = KLineType.fieldLabel,
        color = KLineTheme.colors.inkMuted,
        maxLines = 1,
        modifier = modifier.capHeightBox(KLineType.fieldLabel.fontSize),
    )
}

/**
 * The frame of every field: [label] top-left, [unit] top-right, the value right-aligned at the
 * bottom. [content] draws the value in the style handed to it, shrunk (never wrapped) so that a
 * value of [slotSample] fits the field, and grown up to [ValueLook.maxGrowth].
 */
@Composable
internal fun FieldFrame(
    label: String,
    unit: String?,
    look: ValueLook,
    slotSample: String,
    modifier: Modifier,
    content: @Composable (TextStyle) -> Unit,
) {
    BoxWithConstraints(modifier.padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical)) {
        val style = rememberFittedStyle(look.style, slotSample, maxWidth, maxHeight - LocalDensity.current.labelAndGapHeight(), look.maxGrowth)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                FieldCaption(label)
                Spacer(Modifier.weight(1f))
                if (unit != null) FieldCaption(unit, Modifier.padding(start = Spacing.grid))
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) { content(style) }
        }
    }
}

/**
 * One signal as a field. An id this version does not know (a layout from a newer app) shows "--",
 * dimmed, under its own id, so the choice survives until the user changes it.
 */
@Composable
internal fun DataField(id: String, look: ValueLook, live: LiveState, modifier: Modifier) {
    val signal = remember(id) { Signals.byId(id) }
    if (signal == null) UnknownSignalField(id, look, modifier) else KnownSignalField(signal, look, live, modifier)
}

@Composable
private fun KnownSignalField(signal: Signal, look: ValueLook, live: LiveState, modifier: Modifier) {
    val reading = rememberReading(live.readings, signal.id)
    val stale = rememberIsStale(reading, live.nowMs, signal)
    val tone = rememberValueTone(signal, reading, live.toneContext)
    val noReading = stringResource(R.string.field_no_reading)
    val words = rememberOnOffWords()
    FieldFrame(
        label = fieldLabel(signal),
        unit = signal.unit.takeUnless { labelIsUnit(signal.id) },
        look = look,
        slotSample = slotSample(signal),
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = reading.value?.let { "${signal.name}: ${formatReading(signal, it.value, words)} ${signal.unit}" }
                ?: "${signal.name}: $noReading"
        },
    ) { style -> ValueText(signal, reading, stale, tone, style) }
}

@Composable
private fun UnknownSignalField(id: String, look: ValueLook, modifier: Modifier) {
    FieldFrame(id.uppercase(), unit = null, look = look, slotSample = UNKNOWN_SIGNAL_SAMPLE, modifier = modifier) { style ->
        Text(MISSING_VALUE, style = style, color = KLineTheme.colors.inkFaint, maxLines = 1, softWrap = false, modifier = Modifier.capHeightBox(style.fontSize))
    }
}
