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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
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

internal val LineWidth = 2.dp
internal val LabelGap = Spacing.grid

private val UnknownSignalLook = ValueLook(KLineType.fieldValue)

/**
 * A graph of the last 60 s of one signal: label top-left, the current value top-right, the line
 * in ink and the bottom and top of its axis ([graphAxis]) at the left edge of the plot. A signal
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
                .padding(start = Spacing.fieldPaddingHorizontal, end = Spacing.fieldPaddingHorizontal, bottom = Spacing.fieldPaddingVertical),
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
    Spacer(
        modifier.fillMaxSize().drawWithCache {
            val path = Path()
            val frame = PlotFrame(size, this, measurer, signal)
            val high = ValueLabel(measurer, signal.decimals)
            val low = ValueLabel(measurer, signal.decimals)
            onDrawBehind {
                // Reading the revision is what redraws the graph for a new point.
                val newestRevision = revision.value
                val sinceMs = nowMs.value - GRAPH_WINDOW_MS
                frame.drawRules(this, colors.rule)
                val series = history.of(signal.id)
                val data = series?.rangeSince(sinceMs)
                if (series == null || data == null || newestRevision < 0) return@onDrawBehind

                val axis = graphAxis(data, signal)
                val first = series.firstIndexAtOrAfter(sinceMs)
                path.rewind()
                for (i in first until series.size) {
                    val x = frame.left + (series.timeAt(i) - sinceMs).toFloat() / GRAPH_WINDOW_MS * frame.width
                    val y = frame.yOf(series.valueAt(i), axis)
                    if (i == first) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, colors.ink, style = frame.lineStroke)
                frame.drawAxisLabels(this, high.layoutOf(axis.high), low.layoutOf(axis.low), colors.inkMuted)
            }
        },
    )
}

/**
 * Where a graph draws, shared by the dashboard Graph cell and the lap graph of the trip detail: the
 * plot starts right of the axis labels (as wide as the signal's widest value) and keeps the line its
 * own width clear of the two rules, so a value at an end of the axis is never cut in half.
 */
internal class PlotFrame(size: Size, density: Density, measurer: TextMeasurer, signal: Signal) {
    private val lineInset = with(density) { LineWidth.toPx() }
    private val ruleHeight = with(density) { Spacing.ruleThickness.toPx() }
    private val height = size.height
    private val plotHeight = height - 2 * lineInset
    val left: Float = measurer.measure(slotSample(signal), KLineType.fieldLabel).size.width + with(density) { LabelGap.toPx() }
    val width: Float = size.width - left
    val lineStroke = Stroke(lineInset, join = StrokeJoin.Round)

    /** The y of [value] on [axis]: its high end at the top of the plot, its low end at the bottom. */
    fun yOf(value: Double, axis: ValueRange): Float =
        lineInset + (1f - ((value - axis.low) / (axis.high - axis.low)).toFloat()) * plotHeight

    /** The rules along the top and the bottom of the plot. */
    fun drawRules(scope: DrawScope, color: Color) = with(scope) {
        drawRect(color, Offset(left, 0f), Size(width, ruleHeight))
        drawRect(color, Offset(left, height - ruleHeight), Size(width, ruleHeight))
    }

    /** The two ends of the axis left of the plot, level with the top and the bottom rule. */
    fun drawAxisLabels(scope: DrawScope, high: TextLayoutResult, low: TextLayoutResult, color: Color) = with(scope) {
        drawText(high, color, Offset(0f, 0f))
        drawText(low, color, Offset(0f, height - low.size.height))
    }
}

/** Lays out the text of a value, and keeps the layout while the value stays the same. */
internal class ValueLabel(private val measurer: TextMeasurer, private val decimals: Int) {
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
