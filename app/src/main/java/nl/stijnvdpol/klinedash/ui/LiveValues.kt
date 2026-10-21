package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.obd.Signal
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

/**
 * A reading in a slot of fixed width ([slotSample] measured in [style]): the number may change, the
 * slot and everything beside it do not. Dimmed when stale; a missing value shows [MISSING_VALUE].
 * [sample] sets the slot width when several rows must share one column. Give it
 * `Modifier.alignByBaseline()` from the caller to line the unit up with it.
 */
@Composable
internal fun ValueText(
    signal: Signal,
    reading: State<Reading?>,
    stale: State<Boolean>,
    style: TextStyle,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
    sample: String = slotSample(signal),
) {
    val text by remember(signal) {
        derivedStateOf { reading.value?.let { formatReading(signal, it.value) } ?: MISSING_VALUE }
    }
    val colors = KLineTheme.colors
    val slotWidth = rememberTextWidth(sample, style)
    Text(
        text = text,
        style = style,
        color = if (stale.value) colors.inkMuted else colors.ink,
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.width(slotWidth),
    )
}
