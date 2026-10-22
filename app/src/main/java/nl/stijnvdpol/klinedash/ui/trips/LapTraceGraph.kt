package nl.stijnvdpol.klinedash.ui.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.service.LapTrace
import nl.stijnvdpol.klinedash.service.TracePoint
import nl.stijnvdpol.klinedash.service.SignalDisplay
import nl.stijnvdpol.klinedash.service.display
import nl.stijnvdpol.klinedash.ui.FieldCaption
import nl.stijnvdpol.klinedash.ui.LocalUnitSystem
import nl.stijnvdpol.klinedash.ui.MISSING_VALUE
import nl.stijnvdpol.klinedash.ui.RPM_ID
import nl.stijnvdpol.klinedash.ui.SPEED_ID
import nl.stijnvdpol.klinedash.ui.fieldLabel
import nl.stijnvdpol.klinedash.ui.labelIsUnit
import nl.stijnvdpol.klinedash.ui.pages.PlotFrame
import nl.stijnvdpol.klinedash.ui.pages.ValueLabel
import nl.stijnvdpol.klinedash.ui.pages.ValueRange
import nl.stijnvdpol.klinedash.ui.pages.graphAxis
import nl.stijnvdpol.klinedash.ui.slotSample
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private val PlotHeight = 104.dp

/** One signal of a lap over time: parallel arrays, so drawing reads them by index without allocating. */
private class TraceSeries(val timesMs: LongArray, val values: DoubleArray) {
    val range: ValueRange? = if (values.isEmpty()) null else ValueRange(values.min(), values.max())
}

private fun seriesOf(trace: LapTrace, valueOf: (TracePoint) -> Double?): TraceSeries {
    val points = trace.points.filter { valueOf(it) != null }
    return TraceSeries(LongArray(points.size) { points[it].elapsedMs }, DoubleArray(points.size) { valueOf(points[it]) ?: 0.0 })
}

/**
 * Speed and rpm of one lap over its time, in the style of the dashboard graph cell: label top-left,
 * a 2 dp `ink` line between two rules, the ends of the value axis ([graphAxis]) at the left edge of
 * the plot. The time axis runs from the start to the end of the lap (its lap time); both plots share it.
 */
@Composable
internal fun LapTraceGraph(trace: LapTrace, modifier: Modifier = Modifier) {
    val speed = remember(trace) { seriesOf(trace) { it.speedKmh } }
    val rpm = remember(trace) { seriesOf(trace) { it.rpm } }
    val durationMs = trace.lapTimeMs
    Column(modifier) {
        TracePlot(SPEED_ID, speed, durationMs, Modifier.fillMaxWidth().height(PlotHeight + Spacing.touchMin / 2))
        TracePlot(RPM_ID, rpm, durationMs, Modifier.fillMaxWidth().height(PlotHeight + Spacing.touchMin / 2))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FieldCaption(formatLapTime(0))
            FieldCaption(formatLapTime(durationMs))
        }
    }
}

@Composable
private fun TracePlot(signalId: String, series: TraceSeries, durationMs: Long, modifier: Modifier) {
    val signal = remember(signalId) { Signals.byId(signalId) } ?: return
    val units = LocalUnitSystem.current
    val display = remember(signal, units) { signal.display(units) }
    val colors = KLineTheme.colors
    val peak = series.range?.high?.let(display::format) ?: MISSING_VALUE
    val description = stringResource(R.string.lap_graph_description, signal.name)
    val noData = stringResource(R.string.lap_graph_no_data, signal.shortName.lowercase())
    Column(modifier.semantics { contentDescription = description }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            FieldCaption(fieldLabel(signal) + if (labelIsUnit(signal.id)) "" else " ${display.unit.uppercase()}")
            FieldCaption(stringResource(R.string.lap_graph_max, peak).uppercase())
        }
        if (series.range == null) {
            Text(noData, style = KLineType.caption, color = colors.inkMuted, modifier = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal))
        } else {
            TracePlotCanvas(display, slotSample(signal, units), series, durationMs, Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal))
        }
    }
}

@Composable
private fun TracePlotCanvas(display: SignalDisplay, sample: String, series: TraceSeries, durationMs: Long, modifier: Modifier) {
    val colors = KLineTheme.colors
    val measurer = rememberTextMeasurer()
    Spacer(
        modifier.fillMaxSize().drawWithCache {
            val range = series.range ?: return@drawWithCache onDrawBehind { }
            val axis = graphAxis(ValueRange(display.value(range.low), display.value(range.high)), display)
            val frame = PlotFrame(size, this, measurer, sample)
            val path = Path()
            val high = ValueLabel(measurer, display.decimals).layoutOf(axis.high)
            val low = ValueLabel(measurer, display.decimals).layoutOf(axis.low)
            val span = maxOf(durationMs, 1L).toFloat()
            for (i in series.values.indices) {
                val x = frame.left + series.timesMs[i] / span * frame.width
                val y = frame.yOf(display.value(series.values[i]), axis)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            onDrawBehind {
                frame.drawRules(this, colors.rule)
                drawPath(path, colors.ink, style = frame.lineStroke)
                frame.drawAxisLabels(this, high, low, colors.inkMuted)
            }
        },
    )
}
