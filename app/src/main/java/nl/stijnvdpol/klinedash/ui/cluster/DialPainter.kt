package nl.stijnvdpol.klinedash.ui.cluster

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import nl.stijnvdpol.klinedash.ui.CAUTION_RPM
import nl.stijnvdpol.klinedash.ui.CAP_HEIGHT_EM
import nl.stijnvdpol.klinedash.ui.DANGER_RPM
import nl.stijnvdpol.klinedash.ui.theme.KLineColors
import nl.stijnvdpol.klinedash.ui.theme.NumeralFace
import nl.stijnvdpol.klinedash.ui.theme.contrastRatio
import nl.stijnvdpol.klinedash.ui.theme.numeralStyle

/*
 * The round needle dial of the twin and centre styles. Everything is laid out in "reference dp" of
 * a dial 500 across and scaled to the real size, so a dial looks the same at 390 and at 780 dp. The
 * static parts (face, rim, bands, ticks, numerals) are one painter, drawn once into their own
 * layer; the needle is another, the only thing that is drawn again when a value changes.
 */

/** Diameter of the design the numbers below are given for. */
internal const val DIAL_REF = 500f

private const val PLAIN_RIM = 5f
private const val CHROME_STROKE = 2f
private const val CHROME_STROKES = 3
private const val TICK_OUTER = 236f
private const val MAJOR_TICK_LENGTH = 22f
private const val MINOR_TICK_LENGTH = 11f
private const val MAJOR_TICK_WIDTH = 4f
private const val MINOR_TICK_WIDTH = 2f
private const val MINOR_TICK_ALPHA = 0.6f
private const val BAND_OUTER = 212f
private const val BAND_WIDTH = 8f
private const val NUMERAL_RADIUS = 170f
private const val NUMERAL_SIZE = 36f
private const val CAPTION_SIZE = 16f
private const val TOP_CAPTION_OFFSET = -100f

/** Where the digital readout of a dial stands: its baseline below the centre, and its size. */
internal const val READOUT_BASELINE = 150f
internal const val READOUT_SIZE = 66f
private const val CAPTION_BASELINE = 186f

private const val NEEDLE_TIP = 230f
private const val NEEDLE_TAIL = 38f
private const val NEEDLE_BASE_HALF_WIDTH = 6.5f
private const val NEEDLE_TIP_HALF_WIDTH = 1.8f
private const val NEEDLE_HALO = 3f
private const val HUB_RADIUS = 21f
private const val HUB_CAP_RADIUS = 7f

private const val CHROME_LIGHTEN = 0.55f
private const val CHROME_DARKEN = 0.5f
private const val CHROME_MIN_CONTRAST = 3.0
private const val CAPTION_ALPHA = 0.7f

/**
 * What differs between the tachometer and the speedometer: the scale, its ticks (every
 * [minorStep]; major every [majorStep]; a numeral every [labelStep], showing the value divided by
 * [labelDivisor]), whether the caution and redline zones show, and the captions.
 */
@Immutable
internal class DialSpec(
    val scale: ArcScale,
    val minorStep: Int,
    val majorStep: Int,
    val labelStep: Int,
    val labelDivisor: Int,
    val hasZones: Boolean,
    val topCaption: String?,
    val caption: String,
)

private class PlacedText(val layout: TextLayoutResult, val topLeft: Offset, val color: Color)

/** [text] measured in [style] ([fontPx] big) with the middle of its capitals or digits on [middle]. */
private fun placeCentred(measurer: TextMeasurer, text: String, style: TextStyle, fontPx: Float, middle: Offset, color: Color): PlacedText {
    val layout = measurer.measure(text, style)
    val capHeight = fontPx * CAP_HEIGHT_EM
    return PlacedText(layout, Offset(middle.x - layout.size.width / 2f, middle.y + capHeight / 2f - layout.firstBaseline), color)
}

private fun KLineColors.zoneColour(value: Double, normal: Color): Color = when {
    value >= DANGER_RPM -> danger
    value >= CAUTION_RPM -> caution
    else -> normal
}

/** The face, rim, zone bands, ticks, numerals and captions of one dial of [diameter] pixels. */
internal class DialPainter(
    private val diameter: Float,
    density: Density,
    private val spec: DialSpec,
    private val colors: KLineColors,
    numerals: NumeralFace,
    measurer: TextMeasurer,
) {
    private val unit = diameter / DIAL_REF
    private val center = Offset(diameter / 2f, diameter / 2f)
    private val isChrome = contrastRatio(colors.ring, colors.background) >= CHROME_MIN_CONTRAST
    private val ticks = dialTicks(spec.scale, spec.minorStep, spec.majorStep, spec.labelStep)
    private val numeralStyle = with(density) { numeralStyle(numerals, (NUMERAL_SIZE * unit).toSp()) }
    private val captionStyle = with(density) { numeralStyle(numerals, (CAPTION_SIZE * unit).toSp()) }
    private val numeralTexts = ticks.filter { it.hasLabel }.map { tick ->
        val color = if (spec.hasZones) colors.zoneColour(tick.value, colors.faceInk) else colors.faceInk
        val middle = polar(center.x, center.y, NUMERAL_RADIUS * unit, tick.angleDeg)
        placeCentred(measurer, (tick.value / spec.labelDivisor).toInt().toString(), numeralStyle, NUMERAL_SIZE * unit, middle, color)
    }
    private val captionColor = colors.faceInk.copy(alpha = CAPTION_ALPHA)
    private val captions = listOfNotNull(
        spec.topCaption?.let {
            placeCentred(measurer, it, captionStyle, CAPTION_SIZE * unit, Offset(center.x, center.y + TOP_CAPTION_OFFSET * unit), captionColor)
        },
        placeCentred(
            measurer, spec.caption, captionStyle, CAPTION_SIZE * unit,
            Offset(center.x, center.y + (CAPTION_BASELINE - CAPTION_SIZE * CAP_HEIGHT_EM / 2) * unit), captionColor,
        ),
    )


    fun draw(scope: DrawScope) = with(scope) {
        val rim = if (isChrome) CHROME_STROKE * CHROME_STROKES * unit else PLAIN_RIM * unit
        drawCircle(colors.face, diameter / 2f - rim / 2f, center)
        drawRim()
        if (spec.hasZones) drawZoneBands()
        drawTicks()
        (numeralTexts + captions).forEach { drawText(it.layout, it.color, it.topLeft) }
    }

    private fun DrawScope.drawRim() {
        if (!isChrome) {
            drawCircle(colors.ring, diameter / 2f - PLAIN_RIM * unit / 2f, center, style = Stroke(PLAIN_RIM * unit))
            return
        }
        val chromes = listOf(lerp(colors.ring, Color.White, CHROME_LIGHTEN), colors.ring, lerp(colors.ring, Color.Black, CHROME_DARKEN))
        chromes.forEachIndexed { index, color ->
            val radius = diameter / 2f - (index + 0.5f) * CHROME_STROKE * unit
            drawCircle(color, radius, center, style = Stroke(CHROME_STROKE * unit))
        }
    }

    private fun DrawScope.drawZoneBands() {
        val middle = (BAND_OUTER - BAND_WIDTH / 2f) * unit
        val topLeft = Offset(center.x - middle, center.y - middle)
        val bandSize = Size(middle * 2, middle * 2)
        fun band(from: Double, to: Double, color: Color) {
            val start = spec.scale.angleDeg(from)
            drawArc(color, start, spec.scale.angleDeg(to) - start, false, topLeft, bandSize, style = Stroke(BAND_WIDTH * unit))
        }
        band(CAUTION_RPM.toDouble(), DANGER_RPM.toDouble(), colors.caution)
        band(DANGER_RPM.toDouble(), spec.scale.max, colors.danger)
    }

    private fun DrawScope.drawTicks() {
        ticks.forEach { tick ->
            val isMajor = tick.kind == TickKind.MAJOR
            val length = (if (isMajor) MAJOR_TICK_LENGTH else MINOR_TICK_LENGTH) * unit
            val base = if (isMajor) colors.faceInk else colors.faceInk.copy(alpha = MINOR_TICK_ALPHA)
            val color = if (spec.hasZones) colors.zoneColour(tick.value, base) else base
            drawLine(
                color,
                polar(center.x, center.y, TICK_OUTER * unit, tick.angleDeg),
                polar(center.x, center.y, TICK_OUTER * unit - length, tick.angleDeg),
                strokeWidth = (if (isMajor) MAJOR_TICK_WIDTH else MINOR_TICK_WIDTH) * unit,
            )
        }
    }
}

/**
 * The tapered needle with its hub, for a dial of [diameter] pixels. A thin halo in the face colour
 * keeps it apart from a numeral or zone band of the same colour it passes over.
 */
internal class NeedlePainter(private val diameter: Float, private val scale: ArcScale, private val colors: KLineColors) {
    private val unit = diameter / DIAL_REF
    private val center = Offset(diameter / 2f, diameter / 2f)

    /** Pointing right from the origin; rotated to the value when drawn. */
    private val needle = Path().apply {
        moveTo(NEEDLE_TIP * unit, -NEEDLE_TIP_HALF_WIDTH * unit)
        lineTo(NEEDLE_TIP * unit, NEEDLE_TIP_HALF_WIDTH * unit)
        lineTo(-NEEDLE_TAIL * unit, NEEDLE_BASE_HALF_WIDTH * unit)
        lineTo(-NEEDLE_TAIL * unit, -NEEDLE_BASE_HALF_WIDTH * unit)
        close()
    }

    fun draw(scope: DrawScope, value: Float) = with(scope) {
        translate(center.x, center.y) {
            rotate(scale.angleDeg(value.toDouble()), Offset.Zero) {
                drawPath(needle, colors.face, style = Stroke(NEEDLE_HALO * unit))
                drawPath(needle, colors.needle)
            }
        }
        drawCircle(colors.needle, HUB_RADIUS * unit, center)
        drawCircle(colors.face, HUB_CAP_RADIUS * unit, center)
    }
}
