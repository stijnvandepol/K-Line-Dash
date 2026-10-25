package nl.stijnvdpol.klinedash.ui.cluster

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import nl.stijnvdpol.klinedash.core.alert.AlertSettings
import nl.stijnvdpol.klinedash.data.UnitSystem
import nl.stijnvdpol.klinedash.service.Quantity
import nl.stijnvdpol.klinedash.service.fromMetric
import nl.stijnvdpol.klinedash.ui.CAP_HEIGHT_EM
import nl.stijnvdpol.klinedash.ui.LocalUnitSystem
import nl.stijnvdpol.klinedash.ui.ValueText
import nl.stijnvdpol.klinedash.ui.fieldLabel
import nl.stijnvdpol.klinedash.ui.unitOf
import nl.stijnvdpol.klinedash.ui.theme.KLineColors
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.LocalClusterLook
import nl.stijnvdpol.klinedash.ui.theme.NumeralFace
import nl.stijnvdpol.klinedash.ui.theme.numeralStyle
import kotlin.math.roundToInt

/*
 * The slim coolant dial between the twin dials: a quarter circle across the top, cold left, hot
 * right, its caution and danger bands at the limits of SETUP > Warnings, the value under it. It sits
 * on the background, not on a dial face, so it draws in ink and the status colours. Laid out in
 * reference dp of a 500 dp dial like the big ones.
 */

private const val PIVOT_Y = 302f
private const val ARC_RADIUS = 126f
private const val TRACK_WIDTH = 6f
private const val TICK_GAP = 8f
private const val MAJOR_TICK_LENGTH = 13f
private const val MINOR_TICK_LENGTH = 7f
private const val MAJOR_TICK_WIDTH = 3f
private const val MINOR_TICK_WIDTH = 1.5f
private const val TICK_STEP_C = 10
private const val MAJOR_TICK_STEP_C = 30
private const val TICK_STEP_F = 10
private const val MAJOR_TICK_STEP_F = 50
private const val END_LETTER_RADIUS = 30f
private const val END_LETTER_SIZE = 20f
private const val POINTER_INSET = 20f
private const val POINTER_OUTSET = 5f
private const val POINTER_WIDTH = 4.5f
private const val VALUE_BASELINE = 308f
private const val VALUE_SIZE = 62f
private const val VALUE_SAMPLE = "888"
private const val CAPTION_BASELINE = 344f
private const val CAPTION_SIZE = 17f

/** The ends of the scale, as on a car's temperature gauge. */
private const val COLD_LETTER = "C"
private const val HOT_LETTER = "H"

/** Where the arc sits in a column of [size]: everything scales with its height. */
private class CoolantFrame(val size: Size) {
    val unit = size.height / DIAL_REF
    val pivot = Offset(size.width / 2f, PIVOT_Y * unit)
    val radius = ARC_RADIUS * unit
}

private class CoolantStatic(
    private val frame: CoolantFrame,
    density: Density,
    private val colors: KLineColors,
    private val limits: AlertSettings,
    private val units: UnitSystem,
    measurer: TextMeasurer,
    caption: String,
    numerals: NumeralFace,
) {
    private val scale = coolantScale(units)
    private val size = frame.size
    private val unit = frame.unit
    private val pivot = frame.pivot
    private val radius = frame.radius
    private val ticks = if (units == UnitSystem.METRIC) {
        dialTicks(scale, TICK_STEP_C, MAJOR_TICK_STEP_C, MAJOR_TICK_STEP_C)
    } else {
        dialTicks(scale, TICK_STEP_F, MAJOR_TICK_STEP_F, MAJOR_TICK_STEP_F)
    }
    private val letterStyle = with(density) { numeralStyle(numerals, (END_LETTER_SIZE * unit).toSp()) }
    private val captionStyle = with(density) { numeralStyle(numerals, (CAPTION_SIZE * unit).toSp()) }
    private val letters = listOf(COLD_LETTER to scale.min, HOT_LETTER to scale.max).map { (letter, value) ->
        val layout = measurer.measure(letter, letterStyle)
        val middle = polar(pivot.x, pivot.y, radius + END_LETTER_RADIUS * unit, scale.angleDeg(value))
        val capHeight = END_LETTER_SIZE * unit * CAP_HEIGHT_EM
        layout to Offset(middle.x - layout.size.width / 2f, middle.y + capHeight / 2f - layout.firstBaseline)
    }
    private val captionLayout = measurer.measure(caption, captionStyle)
    private val captionTopLeft = Offset(
        (size.width - captionLayout.size.width) / 2f,
        CAPTION_BASELINE * unit - captionLayout.firstBaseline,
    )

    fun draw(scope: DrawScope) = with(scope) {
        val topLeft = Offset(pivot.x - radius, pivot.y - radius)
        val arcSize = Size(radius * 2, radius * 2)
        val track = Stroke(TRACK_WIDTH * unit)
        fun band(from: Double, to: Double, color: Color) {
            val start = scale.angleDeg(from)
            drawArc(color, start, scale.angleDeg(to) - start, false, topLeft, arcSize, style = track)
        }
        val caution = units.fromMetric(Quantity.TEMPERATURE, limits.coolantCautionC)
        val danger = units.fromMetric(Quantity.TEMPERATURE, limits.coolantDangerC)
        band(scale.min, scale.max, colors.inkFaint)
        band(caution, danger, colors.caution)
        band(danger, scale.max, colors.danger)
        ticks.forEach { tick ->
            val isMajor = tick.kind == TickKind.MAJOR
            val inner = radius + (TRACK_WIDTH / 2 + TICK_GAP) * unit
            val length = (if (isMajor) MAJOR_TICK_LENGTH else MINOR_TICK_LENGTH) * unit
            drawLine(
                colors.inkMuted,
                polar(pivot.x, pivot.y, inner, tick.angleDeg),
                polar(pivot.x, pivot.y, inner + length, tick.angleDeg),
                strokeWidth = (if (isMajor) MAJOR_TICK_WIDTH else MINOR_TICK_WIDTH) * unit,
            )
        }
        letters.forEach { (layout, at) -> drawText(layout, colors.inkMuted, at) }
        drawText(captionLayout, colors.inkMuted, captionTopLeft)
    }
}

private class CoolantPointer(private val frame: CoolantFrame, private val colors: KLineColors, private val scale: ArcScale) {
    /** [shown] is the temperature in the unit of the scale. */
    fun draw(scope: DrawScope, shown: Float) = with(scope) {
        val angle = scale.angleDeg(shown.toDouble())
        drawLine(
            colors.needle,
            polar(frame.pivot.x, frame.pivot.y, frame.radius - POINTER_INSET * frame.unit, angle),
            polar(frame.pivot.x, frame.pivot.y, frame.radius + POINTER_OUTSET * frame.unit, angle),
            strokeWidth = POINTER_WIDTH * frame.unit,
        )
    }
}

/**
 * The coolant dial for a column as tall as the big dials next to it and as wide as [modifier] makes
 * it. The pointer reads the latest reading in the draw phase.
 */
@Composable
internal fun CoolantDial(feed: GaugeFeed, limits: AlertSettings, modifier: Modifier = Modifier) {
    val colors = KLineTheme.colors
    val numerals = LocalClusterLook.current.numerals
    val measurer = rememberTextMeasurer()
    val units = LocalUnitSystem.current
    val caption = "${fieldLabel(feed.signal)} ${unitOf(feed.signal, units)}"
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val dialPx = with(density) { maxHeight.toPx() }
        val unit = dialPx / DIAL_REF
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawWithCache {
                    val painter = CoolantStatic(CoolantFrame(size), this, colors, limits, units, measurer, caption, numerals)
                    onDrawBehind { painter.draw(this) }
                },
        )
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawWithCache {
                    val painter = CoolantPointer(CoolantFrame(size), colors, coolantScale(units))
                    onDrawBehind { painter.draw(this, feed.drawnValue()) }
                },
        )
        val style = remember(numerals, unit, density) { numeralStyle(numerals, with(density) { (VALUE_SIZE * unit).toSp() }) }
        val capHeight = (VALUE_SIZE * unit * CAP_HEIGHT_EM).roundToInt()
        ValueText(
            feed.signal, feed.reading, feed.stale, feed.tone, style,
            Modifier.align(Alignment.TopCenter).offset { IntOffset(0, (VALUE_BASELINE * unit).roundToInt() - capHeight) },
            sample = VALUE_SAMPLE, align = TextAlign.Center,
        )
    }
}
