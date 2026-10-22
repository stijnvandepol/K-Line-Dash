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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.core.obd.Signal
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.service.LapTrace
import nl.stijnvdpol.klinedash.service.TracePoint
import nl.stijnvdpol.klinedash.service.formatNumber
import nl.stijnvdpol.klinedash.ui.FieldCaption
import nl.stijnvdpol.klinedash.ui.MISSING_VALUE
import nl.stijnvdpol.klinedash.ui.fieldLabel
import nl.stijnvdpol.klinedash.ui.labelIsUnit
import nl.stijnvdpol.klinedash.ui.pages.LabelGap
import nl.stijnvdpol.klinedash.ui.pages.LineWidth
import nl.stijnvdpol.klinedash.ui.pages.ValueLabel
import nl.stijnvdpol.klinedash.ui.pages.ValueRange
import nl.stijnvdpol.klinedash.ui.pages.graphAxis
import nl.stijnvdpol.klinedash.ui.slotSample
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private const val SPEED_ID = "speed"
private const val RPM_ID = "rpm"
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
    val colors = KLineTheme.colors
    val peak = series.range?.high?.let { formatNumber(it, signal.decimals) } ?: MISSING_VALUE
    Column(modifier.semantics { contentDescription = "${signal.name} over the lap" }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            FieldCaption(fieldLabel(signal) + if (labelIsUnit(signal.id)) "" else " ${signal.unit.uppercase()}")
            FieldCaption("MAX $peak")
        }
        if (series.range == null) {
            Text("No ${signal.shortName.lowercase()} data in this lap.", style = KLineType.caption, color = colors.inkMuted, modifier = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal))
        } else {
            TracePlotCanvas(signal, series, durationMs, Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal))
        }
    }
}

@Composable
private fun TracePlotCanvas(signal: Signal, series: TraceSeries, durationMs: Long, modifier: Modifier) {
    val colors = KLineTheme.colors
    val measurer = rememberTextMeasurer()
    Spacer(
        modifier.fillMaxSize().drawWithCache {
            val range = series.range ?: return@drawWithCache onDrawBehind { }
            val axis = graphAxis(range, signal)
            val path = Path()
            val stroke = Stroke(LineWidth.toPx(), join = StrokeJoin.Round)
            val lineInset = LineWidth.toPx()
            val plotLeft = measurer.measure(slotSample(signal), KLineType.fieldLabel).size.width + LabelGap.toPx()
            val plotWidth = size.width - plotLeft
            val plotHeight = size.height - 2 * lineInset
            val ruleHeight = Spacing.ruleThickness.toPx()
            val high = ValueLabel(measurer, signal.decimals).layoutOf(axis.high)
            val low = ValueLabel(measurer, signal.decimals).layoutOf(axis.low)
            val span = maxOf(durationMs, 1L).toFloat()
            for (i in series.values.indices) {
                val x = plotLeft + series.timesMs[i] / span * plotWidth
                val y = lineInset + (1f - ((series.values[i] - axis.low) / (axis.high - axis.low)).toFloat()) * plotHeight
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            onDrawBehind {
                drawRect(colors.rule, Offset(plotLeft, 0f), Size(plotWidth, ruleHeight))
                drawRect(colors.rule, Offset(plotLeft, size.height - ruleHeight), Size(plotWidth, ruleHeight))
                drawPath(path, colors.ink, style = stroke)
                drawText(high, colors.inkMuted, Offset(0f, 0f))
                drawText(low, colors.inkMuted, Offset(0f, size.height - low.size.height))
            }
        },
    )
}
