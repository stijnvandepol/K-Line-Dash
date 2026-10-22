package nl.stijnvdpol.klinedash.ui.cluster

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.ui.BATTERY_ID
import nl.stijnvdpol.klinedash.ui.CAP_HEIGHT_EM
import nl.stijnvdpol.klinedash.ui.COOLANT_ID
import nl.stijnvdpol.klinedash.ui.DataField
import nl.stijnvdpol.klinedash.ui.LiveState
import nl.stijnvdpol.klinedash.ui.RPM_ID
import nl.stijnvdpol.klinedash.ui.RPM_BAR_SEGMENTS
import nl.stijnvdpol.klinedash.ui.RpmZone
import nl.stijnvdpol.klinedash.ui.SEGMENT_RPM
import nl.stijnvdpol.klinedash.ui.SPEED_ID
import nl.stijnvdpol.klinedash.ui.ValueLook
import nl.stijnvdpol.klinedash.ui.ValueText
import nl.stijnvdpol.klinedash.ui.fieldLabel
import nl.stijnvdpol.klinedash.ui.litSegmentCount
import nl.stijnvdpol.klinedash.ui.rpmZone
import nl.stijnvdpol.klinedash.ui.theme.KLineColors
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.LocalClusterLook
import nl.stijnvdpol.klinedash.ui.theme.NumeralFace
import nl.stijnvdpol.klinedash.ui.theme.Spacing
import nl.stijnvdpol.klinedash.ui.theme.numeralStyle
import kotlin.math.roundToInt

private val LightsMargin = Spacing.fieldPaddingHorizontal
private val CornerFieldWidth = 220.dp
private val CornerFieldHeight = 104.dp
private val CornerFieldGap = Spacing.grid
private const val LIGHT_GAP_SHARE = 0.3f
private const val UNLIT_ALPHA = 0.25f
private const val STROKE_SHARE = 0.075f
private const val SEGMENT_GAP_DEG = 0.7f
private const val NUMERAL_SIZE_SHARE = 0.05f
private const val NUMERAL_RADIUS_SHARE = 0.115f
private const val CAPTION_SIZE_SHARE = 0.045f
private const val CAPTION_ALPHA = 0.8f
private const val SCALE_STEP_RPM = 1_000

private const val SPEED_SIZE_SHARE = 0.5f
private const val SPEED_BASELINE_SHARE = -0.16f
private const val SPEED_CAPTION_SHARE = -0.075f
private const val RPM_SIZE_SHARE = 0.16f
private const val RPM_BASELINE_SHARE = 0.09f
private const val RPM_CAPTION_SHARE = 0.16f
private const val SPEED_SAMPLE = "888"
private const val RPM_SAMPLE = "8888"

private val CornerLook = ValueLook(KLineType.fieldValue)

/** The colour of shift light [index]: green, then amber, then red, in the status colours of the theme. */
internal fun KLineColors.lightColour(index: Int): Color = when (shiftLightColour(index)) {
    ShiftLightColour.GREEN -> ok
    ShiftLightColour.AMBER -> caution
    ShiftLightColour.RED -> danger
}

private fun KLineColors.zoneColour(zone: RpmZone): Color = when (zone) {
    RpmZone.NORMAL -> needle
    RpmZone.CAUTION -> caution
    RpmZone.DANGER -> danger
}

/** The strip of ten lights across the top of the area, as a left edge and a width per light. */
private class LightStrip(width: Float, layout: ArcLayout, margin: Float) {
    val top = layout.lightsTop
    val height = layout.lightsHeight
    private val gap = height * LIGHT_GAP_SHARE
    val lightWidth = (width - 2 * margin - gap * (SHIFT_LIGHT_COUNT - 1)) / SHIFT_LIGHT_COUNT
    private val left = margin
    private val pitch = lightWidth + gap

    fun left(index: Int) = left + index * pitch
}

/** The unlit lights and segments, the zone tints, the scale numerals and the captions: drawn once. */
private class ArcStatic(
    size: Size,
    density: Density,
    private val colors: KLineColors,
    numerals: NumeralFace,
    measurer: TextMeasurer,
    captions: Pair<String, String>,
    margin: Float,
) {
    private val layout = ArcLayout(size.width, size.height)
    private val strip = LightStrip(size.width, layout, margin)
    private val stroke = layout.radius * STROKE_SHARE
    private val segments = ArcSegments(layout, stroke)
    private val numeralStyle = with(density) { numeralStyle(numerals, (layout.radius * NUMERAL_SIZE_SHARE).toSp()) }
    private val captionStyle = with(density) { numeralStyle(numerals, (layout.radius * CAPTION_SIZE_SHARE).toSp()) }
    private val numeralTexts = (0..RPM_BAR_SEGMENTS * SEGMENT_RPM / SCALE_STEP_RPM).map { thousand ->
        val layoutResult = measurer.measure(thousand.toString(), numeralStyle)
        val radius = layout.radius * (1 - NUMERAL_RADIUS_SHARE) - stroke
        val middle = polar(layout.centerX, layout.centerY, radius, ArcTach.angleDeg(thousand * SCALE_STEP_RPM.toDouble()))
        val capHeight = layout.radius * NUMERAL_SIZE_SHARE * CAP_HEIGHT_EM
        layoutResult to Offset(middle.x - layoutResult.size.width / 2f, middle.y + capHeight / 2f - layoutResult.firstBaseline)
    }
    private val captionTexts = listOf(captions.first to SPEED_CAPTION_SHARE, captions.second to RPM_CAPTION_SHARE).map { (text, share) ->
        val layoutResult = measurer.measure(text, captionStyle)
        layoutResult to Offset(layout.centerX - layoutResult.size.width / 2f, layout.centerY + layout.radius * share - layoutResult.firstBaseline)
    }

    fun draw(scope: DrawScope) = with(scope) {
        for (i in 0 until SHIFT_LIGHT_COUNT) {
            val topLeft = Offset(strip.left(i), strip.top)
            val lightSize = Size(strip.lightWidth, strip.height)
            drawRect(colors.surface, topLeft, lightSize)
            drawRect(colors.lightColour(i), topLeft, lightSize, UNLIT_ALPHA)
        }
        for (i in 0 until RPM_BAR_SEGMENTS) {
            drawArcSegment(segments, i, colors.surface, 1f)
            val zone = rpmZone(i * SEGMENT_RPM)
            if (zone != RpmZone.NORMAL) drawArcSegment(segments, i, colors.zoneColour(zone), UNLIT_ALPHA)
        }
        numeralTexts.forEach { (text, at) -> drawText(text, colors.inkMuted, at) }
        captionTexts.forEach { (text, at) -> drawText(text, colors.inkMuted.copy(alpha = CAPTION_ALPHA), at) }
    }
}

/** Where the tachometer segments go, worked out once per size so that drawing a frame allocates nothing. */
private class ArcSegments(layout: ArcLayout, strokeWidth: Float) {
    private val middle = layout.radius - strokeWidth / 2f
    val topLeft = Offset(layout.centerX - middle, layout.centerY - middle)
    val size = Size(middle * 2, middle * 2)
    val stroke = Stroke(strokeWidth)
    val startDeg = FloatArray(RPM_BAR_SEGMENTS)
    val sweepDeg = FloatArray(RPM_BAR_SEGMENTS)

    init {
        for (i in 0 until RPM_BAR_SEGMENTS) {
            val (start, sweep) = segmentArc(ArcTach, i, RPM_BAR_SEGMENTS, SEGMENT_GAP_DEG)
            startDeg[i] = start
            sweepDeg[i] = sweep
        }
    }
}

private fun DrawScope.drawArcSegment(segments: ArcSegments, index: Int, color: Color, alpha: Float) {
    drawArc(
        color, segments.startDeg[index], segments.sweepDeg[index], false, segments.topLeft, segments.size,
        alpha = alpha, style = segments.stroke,
    )
}

/** The lit lights and segments for the glided rpm: all there is to redraw while the engine speed moves. */
private class ArcLit(size: Size, private val colors: KLineColors, private val shiftRpm: Int, margin: Float) {
    private val layout = ArcLayout(size.width, size.height)
    private val strip = LightStrip(size.width, layout, margin)
    private val segments = ArcSegments(layout, layout.radius * STROKE_SHARE)
    private val lightSize = Size(strip.lightWidth, strip.height)

    fun draw(scope: DrawScope, rpm: Float, isStale: Boolean) = with(scope) {
        for (i in 0 until shiftLightCount(rpm.toDouble(), shiftRpm)) {
            drawRect(colors.lightColour(i), Offset(strip.left(i), strip.top), lightSize)
        }
        for (i in 0 until litSegmentCount(rpm.toDouble())) {
            val color = if (isStale) colors.inkFaint else colors.zoneColour(rpmZone(i * SEGMENT_RPM))
            drawArcSegment(segments, i, color, 1f)
        }
    }
}

/**
 * Digital arc: ten shift lights across the top (green, amber, red over the last 1 500 rpm before
 * [shiftRpm]), below them a 200 degree segmented tachometer arc with the speed as the hero numeral
 * in its middle and the rpm under it; the coolant and the battery in the two bottom corners, where
 * the arc leaves room.
 */
@Composable
internal fun ArcCluster(live: LiveState, shiftRpm: Int, modifier: Modifier = Modifier) {
    val rpm = rememberGaugeFeed(live, RPM_ID, glides = true)
    val speed = rememberGaugeFeed(live, SPEED_ID, glides = false)
    val colors = KLineTheme.colors
    val numerals = LocalClusterLook.current.numerals
    val measurer = rememberTextMeasurer()
    val captions = fieldLabel(speed.signal) to fieldLabel(rpm.signal)
    BoxWithConstraints(modifier.clipToBounds()) {
        val density = LocalDensity.current
        val layout = remember(constraints.maxWidth, constraints.maxHeight) { ArcLayout(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()) }
        val margin = with(density) { LightsMargin.toPx() }
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawWithCache {
                    val painter = ArcStatic(size, this, colors, numerals, measurer, captions, margin)
                    onDrawBehind { painter.draw(this) }
                },
        )
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val painter = ArcLit(size, colors, shiftRpm, margin)
                    onDrawBehind { painter.draw(this, rpm.drawnValue(), rpm.stale.value) }
                },
        )
        ArcReadout(speed, layout, SPEED_SIZE_SHARE, SPEED_BASELINE_SHARE, SPEED_SAMPLE)
        ArcReadout(rpm, layout, RPM_SIZE_SHARE, RPM_BASELINE_SHARE, RPM_SAMPLE)
        val cornerWidth = minOf(CornerFieldWidth, with(density) { layout.sideRoom.toDp() } - CornerFieldGap)
        DataField(COOLANT_ID, CornerLook, live, Modifier.align(Alignment.BottomStart).width(cornerWidth).height(CornerFieldHeight))
        DataField(BATTERY_ID, CornerLook, live, Modifier.align(Alignment.BottomEnd).width(cornerWidth).height(CornerFieldHeight))
    }
}

/** A digital value centred in the arc, [sizeShare] of the arc's radius big, its baseline [baselineShare] radii from the arc's centre. */
@Composable
private fun ArcReadout(feed: GaugeFeed, layout: ArcLayout, sizeShare: Float, baselineShare: Float, sample: String) {
    val density = LocalDensity.current
    val colors = KLineTheme.colors
    val numerals = LocalClusterLook.current.numerals
    val fontPx = layout.radius * sizeShare
    val style = remember(numerals, fontPx, density) { numeralStyle(numerals, with(density) { fontPx.toSp() }) }
    val capHeight = (fontPx * CAP_HEIGHT_EM).roundToInt()
    val top = (layout.centerY + layout.radius * baselineShare).roundToInt() - capHeight
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        ValueText(
            feed.signal, feed.reading, feed.stale, feed.tone, style,
            Modifier.offset { IntOffset(0, top) }, sample, ink = colors.ink, align = TextAlign.Center,
        )
    }
}
