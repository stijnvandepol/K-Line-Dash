package nl.stijnvdpol.klinedash.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.obd.Signal
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.service.formatNumber
import nl.stijnvdpol.klinedash.ui.DataField
import nl.stijnvdpol.klinedash.ui.FieldCaption
import nl.stijnvdpol.klinedash.ui.LiveState
import nl.stijnvdpol.klinedash.ui.ValueLook
import nl.stijnvdpol.klinedash.ui.ValueText
import nl.stijnvdpol.klinedash.ui.fieldLabel
import nl.stijnvdpol.klinedash.ui.labelIsUnit
import nl.stijnvdpol.klinedash.ui.rememberIsStale
import nl.stijnvdpol.klinedash.ui.rememberReading
import nl.stijnvdpol.klinedash.ui.rememberValueTone
import nl.stijnvdpol.klinedash.ui.slotSample
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing
import kotlin.math.pow

private val LineWidth = 2.dp
private val LabelGap = 8.dp
private val PlotPaddingBottom = 12.dp

/** A flat line sits in the middle of this share of the signal's display range instead of filling the cell. */
private const val MIN_SPAN_SHARE = 0.05

private val UnknownSignalLook = ValueLook(KLineType.fieldValue)

/**
 * A graph of the last 60 s of one signal: label top-left, the current value top-right, the line
 * in ink and the lowest and highest value of the window at the left edge of the plot. A signal
 * this version does not know shows as an empty field.
 */
@Composable
internal fun GraphCell(signalId: String, live: LiveState, history: GraphHistory, revision: State<Int>, modifier: Modifier) {
    val signal = remember(signalId) { Signals.byId(signalId) }
    if (signal == null) {
        DataField(signalId, UnknownSignalLook, live, modifier)
    } else {
        KnownGraphCell(signal, live, history, revision, modifier)
    }
}

@Composable
private fun KnownGraphCell(signal: Signal, live: LiveState, history: GraphHistory, revision: State<Int>, modifier: Modifier) {
    val reading = rememberReading(live.readings, signal.id)
    val stale = rememberIsStale(reading, live.nowMs, signal)
    val tone = rememberValueTone(signal, reading, live.toneContext)
    val label = fieldLabel(signal) + if (labelIsUnit(signal.id)) "" else " ${signal.unit}"
    val description = stringResource(R.string.graph_description, signal.name)
    Column(modifier.semantics { contentDescription = description }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FieldCaption(label)
            ValueText(signal, reading, stale, tone, KLineType.fieldValue)
        }
        GraphPlot(
            signal, live.nowMs, history, revision,
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = Spacing.fieldPaddingHorizontal, end = Spacing.fieldPaddingHorizontal, bottom = PlotPaddingBottom),
        )
    }
}

/**
 * The plot, drawn in the draw phase only: [nowMs] (once a second, so the line scrolls) and
 * [revision] (a new point) are read there, so nothing recomposes. One [Path] is reused for every
 * draw and the points are read from the ring buffer by index, so a draw allocates no objects per point.
 */
@Composable
private fun GraphPlot(signal: Signal, nowMs: State<Long>, history: GraphHistory, revision: State<Int>, modifier: Modifier) {
    val colors = KLineTheme.colors
    val measurer = rememberTextMeasurer()
    val minSpan = remember(signal) { minPlotSpan(signal) }
    Spacer(
        modifier.fillMaxSize().drawWithCache {
            val path = Path()
            val stroke = Stroke(LineWidth.toPx(), join = StrokeJoin.Round)
            val lineInset = LineWidth.toPx()
            val high = ValueLabel(measurer, signal.decimals)
            val low = ValueLabel(measurer, signal.decimals)
            val plotLeft = measurer.measure(slotSample(signal), KLineType.fieldLabel).size.width + LabelGap.toPx()
            val plotWidth = size.width - plotLeft
            val ruleHeight = Spacing.ruleThickness.toPx()
            onDrawBehind {
                // Reading the revision is what redraws the graph for a new point.
                val newestRevision = revision.value
                val sinceMs = nowMs.value - GRAPH_WINDOW_MS
                drawRect(colors.rule, Offset(plotLeft, 0f), Size(plotWidth, ruleHeight))
                drawRect(colors.rule, Offset(plotLeft, size.height - ruleHeight), Size(plotWidth, ruleHeight))
                val series = history.of(signal.id)
                val data = series?.rangeSince(sinceMs)
                if (series == null || data == null || newestRevision < 0) return@onDrawBehind

                val range = plotRange(data, minSpan)
                val plotHeight = size.height - 2 * lineInset
                val first = series.firstIndexAtOrAfter(sinceMs)
                path.rewind()
                for (i in first until series.size) {
                    val x = plotLeft + (series.timeAt(i) - sinceMs).toFloat() / GRAPH_WINDOW_MS * plotWidth
                    val share = ((series.valueAt(i) - range.low) / (range.high - range.low)).toFloat()
                    val y = lineInset + (1f - share) * plotHeight
                    if (i == first) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, colors.ink, style = stroke)

                val highLayout = high.layoutOf(data.high)
                val lowLayout = low.layoutOf(data.low)
                drawText(highLayout, colors.inkMuted, Offset(0f, 0f))
                drawText(lowLayout, colors.inkMuted, Offset(0f, size.height - lowLayout.size.height))
            }
        },
    )
}

private fun minPlotSpan(signal: Signal): Double {
    val oneDisplayStep = 10.0.pow(-signal.decimals)
    return maxOf((signal.max - signal.min) * MIN_SPAN_SHARE, oneDisplayStep)
}

/** Lays out the text of a value, and keeps the layout while the value stays the same. */
private class ValueLabel(private val measurer: TextMeasurer, private val decimals: Int) {
    private var value = Double.NaN
    private var layout: TextLayoutResult? = null

    fun layoutOf(newValue: Double): TextLayoutResult {
        layout?.let { if (newValue == value) return it }
        return measurer.measure(formatNumber(newValue, decimals), KLineType.fieldLabel).also {
            value = newValue
            layout = it
        }
    }
}
