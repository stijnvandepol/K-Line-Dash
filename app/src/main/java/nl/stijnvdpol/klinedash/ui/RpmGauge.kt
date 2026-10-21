package nl.stijnvdpol.klinedash.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.obd.Signal
import nl.stijnvdpol.klinedash.ui.theme.KLineColors
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val LabelBand = 32.dp
private val ArcWidth = 20.dp
private val TickGap = 6.dp
private val MajorTickLength = 16.dp
private val MinorTickLength = 8.dp
private val MajorTickWidth = 3.dp
private val MinorTickWidth = 2.dp
private val NeedleWidth = 4.dp
private const val NEEDLE_INNER_FRACTION = 0.68f
private const val SIN_30_DEGREES = 0.5f

/** The number labels hang a little below the end points of the arc (half a label height). */
private const val BOTTOM_LABEL_OVERHANG_DP = 12f
private const val RPM_PER_THOUSAND = 1_000

/** Radius of the whole gauge (to the outside of the number labels) and the y of its centre. */
internal data class GaugeGeometry(val outerRadius: Dp, val centerY: Dp)

/**
 * Largest gauge that fits: limited by the width, or by the height, which holds the full radius above
 * the centre but only half of it below (the sweep leaves the bottom open). Centred vertically.
 */
internal fun gaugeGeometry(width: Dp, height: Dp): GaugeGeometry {
    val radius = min(width.value / 2, (height.value - BOTTOM_LABEL_OVERHANG_DP) / (1 + SIN_30_DEGREES))
    val usedHeight = radius * (1 + SIN_30_DEGREES) + BOTTOM_LABEL_OVERHANG_DP
    return GaugeGeometry(radius.dp, ((height.value - usedHeight) / 2 + radius).dp)
}

/**
 * The 240 degree RPM gauge with the value inside it. The needle is the only moving part: an
 * [Animatable] that travels to each new reading in about the time between two readings, and it is
 * read in the draw phase only, so a frame of movement redraws two shapes and recomposes nothing.
 */
@Composable
internal fun RpmGauge(
    rpmSignal: Signal,
    reading: State<Reading?>,
    stale: State<Boolean>,
    needleDurationMs: State<Int>,
    modifier: Modifier = Modifier,
) {
    val needle = remember { Animatable(0f) }
    LaunchedEffect(needle) {
        snapshotFlow { reading.value?.value ?: 0.0 }.collectLatest { target ->
            needle.animateTo(target.toFloat(), tween(needleDurationMs.value, easing = LinearEasing))
        }
    }
    val colors = KLineTheme.colors
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val geometry = remember(maxWidth, maxHeight) { gaugeGeometry(maxWidth, maxHeight) }
        val maxRpm = rpmSignal.max
        // Static layer: its own render node, so the moving needle never makes it redraw.
        Spacer(Modifier.fillMaxSize().graphicsLayer().gaugeFace(geometry, colors, measurer, maxRpm))
        Spacer(Modifier.fillMaxSize().gaugeNeedle(geometry, colors, needle, stale, maxRpm))
        Box(
            Modifier
                .fillMaxSize()
                .offset(y = geometry.centerY - maxHeight / 2),
            contentAlignment = Alignment.Center,
        ) {
            RpmValue(rpmSignal, reading, stale)
        }
    }
}

@Composable
private fun RpmValue(rpmSignal: Signal, reading: State<Reading?>, stale: State<Boolean>) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        ValueText(rpmSignal, reading, stale, KLineType.heroValue, TextAlign.End)
        Text(rpmSignal.unit, style = KLineType.label, color = KLineTheme.colors.inkMuted)
    }
}

/** Gauge dimensions in pixels, derived from [GaugeGeometry] for one draw size. */
private class GaugeMetrics(density: Density, geometry: GaugeGeometry, widthPx: Float) {
    val center: Offset
    val outerRadius: Float
    val arcRadius: Float
    val arcInnerRadius: Float
    val arcTopLeft: Offset
    val arcSize: Size
    val arcWidth: Float

    init {
        with(density) {
            center = Offset(widthPx / 2, geometry.centerY.toPx())
            outerRadius = geometry.outerRadius.toPx()
            arcWidth = ArcWidth.toPx()
            val arcOuterRadius = outerRadius - LabelBand.toPx()
            arcRadius = arcOuterRadius - arcWidth / 2
            arcInnerRadius = arcOuterRadius - arcWidth
            arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)
            arcSize = Size(arcRadius * 2, arcRadius * 2)
        }
    }

    fun pointAt(angleDegrees: Float, radius: Float): Offset {
        val radians = Math.toRadians(angleDegrees.toDouble())
        return Offset(center.x + radius * cos(radians).toFloat(), center.y + radius * sin(radians).toFloat())
    }
}

/** Track, redline zone, ticks and numbers: everything that never moves. Rebuilt only on a size change. */
private fun Modifier.gaugeFace(
    geometry: GaugeGeometry,
    colors: KLineColors,
    measurer: TextMeasurer,
    maxRpm: Double,
): Modifier = drawWithCache {
    val metrics = GaugeMetrics(this, geometry, size.width)
    val trackStroke = Stroke(width = metrics.arcWidth)
    val redlineStart = GaugeScale.fraction(GaugeScale.REDLINE_RPM, maxRpm)
    val labelRadius = metrics.outerRadius - LabelBand.toPx() / 2
    val tickOuterRadius = metrics.arcInnerRadius - TickGap.toPx()
    val labels = (0..(maxRpm / RPM_PER_THOUSAND).toInt()).map { measurer.measure(it.toString(), KLineType.label) }

    onDrawBehind {
        drawArc(
            colors.line, GaugeScale.START_ANGLE, GaugeScale.SWEEP_DEGREES, false,
            metrics.arcTopLeft, metrics.arcSize, style = trackStroke,
        )
        drawArc(
            colors.danger, GaugeScale.angleFor(redlineStart), GaugeScale.SWEEP_DEGREES * (1 - redlineStart), false,
            metrics.arcTopLeft, metrics.arcSize, style = trackStroke,
        )
        for (rpm in 0..maxRpm.toInt() step GaugeScale.MINOR_STEP_RPM) {
            val isMajor = rpm % GaugeScale.MAJOR_STEP_RPM == 0
            val angle = GaugeScale.angleFor(GaugeScale.fraction(rpm.toDouble(), maxRpm))
            val color = if (rpm >= GaugeScale.REDLINE_RPM) colors.danger else colors.inkMuted
            val length = (if (isMajor) MajorTickLength else MinorTickLength).toPx()
            drawLine(
                color,
                metrics.pointAt(angle, tickOuterRadius),
                metrics.pointAt(angle, tickOuterRadius - length),
                strokeWidth = (if (isMajor) MajorTickWidth else MinorTickWidth).toPx(),
            )
            if (isMajor) {
                val label = labels[rpm / RPM_PER_THOUSAND]
                val middle = metrics.pointAt(angle, labelRadius)
                drawText(
                    label, color,
                    topLeft = Offset(middle.x - label.size.width / 2f, middle.y - label.size.height / 2f),
                )
            }
        }
    }
}

/** Filled arc up to the needle and the needle itself, redrawn on every animation frame. */
private fun Modifier.gaugeNeedle(
    geometry: GaugeGeometry,
    colors: KLineColors,
    needle: Animatable<Float, *>,
    stale: State<Boolean>,
    maxRpm: Double,
): Modifier = drawWithCache {
    val metrics = GaugeMetrics(this, geometry, size.width)
    val fillStroke = Stroke(width = metrics.arcWidth)
    val needleStroke = NeedleWidth.toPx()
    val needleInner = metrics.arcInnerRadius * NEEDLE_INNER_FRACTION
    val needleOuter = metrics.arcRadius

    onDrawBehind {
        val rpm = needle.value
        val color = if (stale.value) colors.inkMuted else colors.ink
        // The fill stops at the redline: that zone stays red whatever the engine does.
        val fillFraction = GaugeScale.fraction(min(rpm.toDouble(), GaugeScale.REDLINE_RPM), maxRpm)
        if (fillFraction > 0f) {
            drawArc(
                color, GaugeScale.START_ANGLE, GaugeScale.SWEEP_DEGREES * fillFraction, false,
                metrics.arcTopLeft, metrics.arcSize, style = fillStroke,
            )
        }
        val angle = GaugeScale.angleFor(GaugeScale.fraction(rpm.toDouble(), maxRpm))
        drawLine(
            color, metrics.pointAt(angle, needleInner), metrics.pointAt(angle, needleOuter),
            strokeWidth = needleStroke, cap = StrokeCap.Round,
        )
    }
}
