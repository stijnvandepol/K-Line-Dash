package nl.stijnvdpol.klinedash.ui.cluster

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.data.ClusterStyle
import nl.stijnvdpol.klinedash.data.UnitSystem
import nl.stijnvdpol.klinedash.ui.theme.KLineColors
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private val PreviewWidth = 72.dp
private val PreviewHeight = 44.dp

private const val BAR_SEGMENTS = 18
private const val BAR_LIT = 8
private const val FIELD_COUNT = 6
private const val FIELD_ROW_SHARE = 0.16f
private const val MARGIN_SHARE = 0.06f
private const val LIGHT_COUNT = 10
private const val LIT_LIGHTS = 5
private const val ARC_SEGMENTS = 14
private const val ARC_LIT = 8
private const val ARC_GAP_DEG = 3f
private const val ZONE_ALPHA = 0.45f
private const val NEEDLE_FRACTION = 0.45f

/**
 * A small still drawing of cluster [style] in the colours of [colors], for the theme list. It is a
 * sketch of the arrangement (a few rectangles, circles and arcs), nothing like the real renderers,
 * so a list of ten of them costs next to nothing.
 */
@Composable
internal fun ClusterPreview(colors: KLineColors, style: ClusterStyle, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(PreviewWidth, PreviewHeight)
            .border(Spacing.ruleThickness, KLineTheme.colors.inkFaint)
            .clipToBounds()
            .drawBehind {
                drawRect(colors.background)
                when (style) {
                    ClusterStyle.BAR -> drawBarSketch(colors)
                    ClusterStyle.TWIN -> drawTwinSketch(colors)
                    ClusterStyle.CENTER -> drawCenterSketch(colors)
                    ClusterStyle.ARC -> drawArcSketch(colors)
                    ClusterStyle.VIRTUAL -> drawVirtualSketch(colors)
                }
            },
    )
}

/** The row of small fields every style keeps at the bottom, and the height left above it. */
private fun DrawScope.drawFieldRow(colors: KLineColors): Float {
    val rowHeight = size.height * FIELD_ROW_SHARE
    val top = size.height - rowHeight
    val cell = size.width / FIELD_COUNT
    drawRect(colors.rule, Offset(0f, top), Size(size.width, Spacing.ruleThickness.toPx()))
    for (i in 0 until FIELD_COUNT) {
        drawRect(colors.inkMuted, Offset(i * cell + cell * 0.2f, top + rowHeight * 0.35f), Size(cell * 0.6f, rowHeight * 0.3f))
    }
    return top
}

private fun DrawScope.drawBarSketch(colors: KLineColors) {
    val top = drawFieldRow(colors)
    val margin = size.width * MARGIN_SHARE
    val pitch = (size.width - 2 * margin) / BAR_SEGMENTS
    val barHeight = top * 0.38f
    for (i in 0 until BAR_SEGMENTS) {
        val at = Offset(margin + i * pitch, top * 0.12f)
        val segment = Size(pitch - 1.dp.toPx(), barHeight)
        drawRect(colors.surface, at, segment)
        when {
            i < BAR_LIT -> drawRect(colors.accent, at, segment)
            i >= BAR_SEGMENTS * 3 / 4 -> drawRect(colors.danger, at, segment, ZONE_ALPHA)
            i >= BAR_SEGMENTS * 2 / 3 -> drawRect(colors.caution, at, segment, ZONE_ALPHA)
        }
    }
    // Three primary numerals under the bar.
    val numeralTop = top * 0.62f
    listOf(0.06f to 0.4f, 0.52f to 0.18f, 0.78f to 0.16f).forEach { (x, w) ->
        drawRect(colors.ink, Offset(size.width * x, numeralTop), Size(size.width * w, top * 0.26f))
    }
}

private fun DrawScope.drawDial(colors: KLineColors, center: Offset, radius: Float, needleAngle: Float) {
    drawCircle(colors.face, radius, center)
    drawCircle(colors.ring, radius, center, style = Stroke(1.5.dp.toPx()))
    val tip = polar(center.x, center.y, radius * 0.85f, needleAngle)
    drawLine(colors.needle, center, tip, strokeWidth = 2.dp.toPx())
    drawCircle(colors.needle, radius * 0.12f, center)
}

private fun DrawScope.drawTwinSketch(colors: KLineColors) {
    val top = drawFieldRow(colors)
    val radius = top * 0.45f
    val y = top / 2f
    drawDial(colors, Offset(size.width * 0.27f, y), radius, DialTach.angleDeg(NEEDLE_FRACTION * TACH_MAX_RPM))
    drawDial(colors, Offset(size.width * 0.73f, y), radius, dialSpeedo(UnitSystem.METRIC).angleDeg(NEEDLE_FRACTION * SPEEDO_MAX_KMH))
    val mid = polar(size.width / 2f, y + radius * 0.5f, radius * 0.45f, 270f)
    drawArc(colors.inkMuted, 225f, 90f, false, Offset(size.width / 2f - radius * 0.45f, y + radius * 0.05f), Size(radius * 0.9f, radius * 0.9f), style = Stroke(1.5.dp.toPx()))
    drawCircle(colors.ink, 1.5.dp.toPx(), mid)
}

private fun DrawScope.drawCenterSketch(colors: KLineColors) {
    val top = drawFieldRow(colors)
    val radius = top * 0.46f
    val y = top / 2f
    drawDial(colors, Offset(size.width / 2f, y), radius, DialTach.angleDeg(NEEDLE_FRACTION * TACH_MAX_RPM))
    drawRect(colors.ink, Offset(size.width * 0.06f, y + top * 0.1f), Size(size.width * 0.22f, top * 0.26f))
    drawRect(colors.ink, Offset(size.width * 0.72f, y - top * 0.32f), Size(size.width * 0.2f, top * 0.22f))
    drawRect(colors.ink, Offset(size.width * 0.72f, y + top * 0.12f), Size(size.width * 0.2f, top * 0.22f))
}

private fun DrawScope.drawArcSketch(colors: KLineColors) {
    val top = drawFieldRow(colors)
    val margin = size.width * MARGIN_SHARE
    val pitch = (size.width - 2 * margin) / LIGHT_COUNT
    for (i in 0 until LIGHT_COUNT) {
        val tone = colors.lightColour(i)
        val at = Offset(margin + i * pitch, top * 0.07f)
        val light = Size(pitch - 1.5.dp.toPx(), top * 0.1f)
        drawRect(colors.surface, at, light)
        drawRect(tone, at, light, if (i < LIT_LIGHTS) 1f else ZONE_ALPHA / 2)
    }
    val radius = top * 0.62f
    val center = Offset(size.width / 2f, top * 0.22f + radius)
    for (i in 0 until ARC_SEGMENTS) {
        val (start, sweep) = segmentArc(ArcTach, i, ARC_SEGMENTS, ARC_GAP_DEG)
        drawArc(
            if (i < ARC_LIT) colors.needle else colors.surface, start, sweep, false,
            Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2), style = Stroke(top * 0.1f),
        )
    }
    drawRect(colors.ink, Offset(size.width * 0.4f, center.y - top * 0.3f), Size(size.width * 0.2f, top * 0.22f))
}

private fun DrawScope.drawVirtualSketch(colors: KLineColors) {
    val top = drawFieldRow(colors)
    val radius = top * 0.48f
    val y = top / 2f
    val leftCenter = Offset(radius * 0.64f, y)
    val rightCenter = Offset(size.width - radius * 0.64f, y)
    listOf(leftCenter to true, rightCenter to false).forEach { (center, isLeft) ->
        drawCircle(colors.ring, radius, center, style = Stroke(1.5.dp.toPx()))
        val scale = if (isLeft) LeftRingTach else rightRingSpeed(UnitSystem.METRIC)
        val start = scale.startDeg
        drawArc(colors.needle, start, scale.sweepDeg * 0.5f, false, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2), style = Stroke(3.dp.toPx()))
        drawRect(colors.ink, Offset(center.x - radius * 0.25f, y - top * 0.08f), Size(radius * 0.5f, top * 0.16f))
    }
    for (row in 0 until 4) {
        val rowY = top * (0.18f + row * 0.19f)
        drawRect(colors.inkMuted, Offset(size.width * 0.42f, rowY), Size(size.width * 0.16f, 2.dp.toPx()))
    }
}
