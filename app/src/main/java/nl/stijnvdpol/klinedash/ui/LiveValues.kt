package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.obd.Signal
import nl.stijnvdpol.klinedash.ui.theme.KLineColors
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme

/*
 * Live values are passed around as State objects and read as late as possible. The poller emits a
 * new readings map 2-5 times a second; whoever reads `readings.value` recomposes on every one of
 * them, so only these small derived states do, and each one only invalidates its reader when its
 * own result changes.
 */

@Composable
internal fun rememberReading(readings: State<Map<String, Reading>>, id: String): State<Reading?> =
    remember(readings, id) { derivedStateOf { readings.value[id] } }

/** No reading at all counts as stale, so a missing value is dimmed like an old one. */
@Composable
internal fun rememberIsStale(reading: State<Reading?>, nowMs: State<Long>, signal: Signal): State<Boolean> =
    remember(reading, nowMs, signal) {
        derivedStateOf { reading.value?.let { isStale(signal, it.timestampMs, nowMs.value) } ?: true }
    }

/** Flips only when the engine starts or stops, not on every rpm reading. */
@Composable
internal fun rememberEngineRunning(readings: State<Map<String, Reading>>): State<Boolean> =
    remember(readings) { derivedStateOf { isEngineRunning(readings.value[RPM_ID]?.value) } }

@Composable
internal fun rememberValueTone(
    signal: Signal,
    reading: State<Reading?>,
    engineRunning: State<Boolean>,
): State<ValueTone> = remember(signal, reading, engineRunning) {
    derivedStateOf {
        reading.value?.let { valueTone(signal.id, it.value, engineRunning.value) } ?: ValueTone.NORMAL
    }
}

@Composable
internal fun rememberOnOffWords(): OnOffWords {
    val on = stringResource(R.string.value_on)
    val off = stringResource(R.string.value_off)
    return remember(on, off) { OnOffWords(on, off) }
}

private fun KLineColors.valueColor(stale: Boolean, tone: ValueTone): Color = when {
    stale -> inkFaint
    tone == ValueTone.DANGER -> danger
    tone == ValueTone.CAUTION -> caution
    else -> ink
}

/**
 * Factor by which a value's font size is scaled to fit its field: never wider than the field, never
 * taller than the room above the baseline, and at most [maxGrowth] times the design size (1 = the
 * value only shrinks, so a head unit narrower than 1280 dp scales it down instead of wrapping).
 */
internal fun valueScale(slotWidth: Float, availableWidth: Float, digitHeight: Float, availableHeight: Float, maxGrowth: Float): Float =
    minOf(maxGrowth, availableWidth / slotWidth, availableHeight / digitHeight)

@Composable
internal fun rememberFittedStyle(
    style: TextStyle,
    sample: String,
    availableWidth: Dp,
    availableHeight: Dp,
    maxGrowth: Float = 1f,
): TextStyle {
    val slotWidth = rememberTextWidth(sample, style)
    val density = LocalDensity.current
    return remember(style, slotWidth, availableWidth, availableHeight, maxGrowth, density) {
        val digitHeight = with(density) { style.fontSize.toDp().value } * CAP_HEIGHT_EM
        val scale = valueScale(slotWidth.value, availableWidth.value, digitHeight, availableHeight.value, maxGrowth)
        if (scale == 1f) style else style.scaledBy(scale)
    }
}

private fun TextStyle.scaledBy(factor: Float): TextStyle =
    copy(fontSize = fontSize * factor, lineHeight = lineHeight * factor)

/**
 * A reading right-aligned in a slot of fixed width ([slotSample] measured in [style]): the number
 * may change, the slot and everything beside it do not. Dimmed when stale, amber or red when it
 * is outside its limits; a missing value shows [MISSING_VALUE]. The box is as tall as the digits
 * and its baseline is the bottom edge, so `Modifier.alignByBaseline()` and padding work from the
 * glyphs. [sample] sets the slot width when several rows must share one column.
 */
@Composable
internal fun ValueText(
    signal: Signal,
    reading: State<Reading?>,
    stale: State<Boolean>,
    tone: State<ValueTone>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    sample: String = slotSample(signal),
) {
    val words = rememberOnOffWords()
    val text by remember(signal, words) {
        derivedStateOf { reading.value?.let { formatReading(signal, it.value, words) } ?: MISSING_VALUE }
    }
    val slotWidth = rememberTextWidth(sample, style)
    Text(
        text = text,
        style = style,
        color = KLineTheme.colors.valueColor(stale.value, tone.value),
        textAlign = TextAlign.End,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.width(slotWidth).capHeightBox(style.fontSize),
    )
}
