package nl.stijnvdpol.klinedash.ui.cluster

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.data.MainPage
import nl.stijnvdpol.klinedash.data.UnitSystem
import nl.stijnvdpol.klinedash.ui.CAP_HEIGHT_EM
import nl.stijnvdpol.klinedash.ui.CAUTION_RPM
import nl.stijnvdpol.klinedash.ui.DANGER_RPM
import nl.stijnvdpol.klinedash.ui.FieldCaption
import nl.stijnvdpol.klinedash.ui.HorizontalRule
import nl.stijnvdpol.klinedash.ui.LiveState
import nl.stijnvdpol.klinedash.ui.LocalUnitSystem
import nl.stijnvdpol.klinedash.ui.RPM_ID
import nl.stijnvdpol.klinedash.ui.SPEED_ID
import nl.stijnvdpol.klinedash.ui.ValueText
import nl.stijnvdpol.klinedash.ui.fieldLabel
import nl.stijnvdpol.klinedash.ui.labelIsUnit
import nl.stijnvdpol.klinedash.ui.unitOf
import nl.stijnvdpol.klinedash.ui.theme.KLineColors
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.LocalClusterLook
import nl.stijnvdpol.klinedash.ui.theme.NumeralFace
import nl.stijnvdpol.klinedash.ui.theme.Spacing
import nl.stijnvdpol.klinedash.ui.theme.numeralStyle
import kotlin.math.roundToInt

/*
 * Virtual cockpit: two thin rings, tach on the left and speed on the right, each centred so that
 * the screen edge cuts a good part of it off, big light numerals in their middles, and a calm list
 * of four values between them. Laid out in reference dp of a ring of radius 250.
 */

private const val RING_REF = 250f
private const val TRACK_WIDTH = 3f
private const val GAUGE_WIDTH = 4f
private const val LIT_WIDTH = 6f
private const val TICK_INSET = 9f
private const val MAJOR_TICK_LENGTH = 14f
private const val MINOR_TICK_LENGTH = 7f
private const val MAJOR_TICK_WIDTH = 2f
private const val MINOR_TICK_WIDTH = 1f
private const val NUMERAL_RADIUS = 50f
private const val NUMERAL_SIZE = 18f
private const val MARKER_INSET = 20f
private const val MARKER_OUTSET = 9f
private const val MARKER_WIDTH = 3.5f
private const val TRACK_ALPHA = 0.45f

private const val READOUT_SIZE_SHARE = 0.46f
private const val READOUT_BASELINE_SHARE = 0.17f
private const val CAPTION_SIZE_SHARE = 0.075f
private const val CAPTION_BASELINE_SHARE = 0.31f
private const val RPM_SAMPLE = "8888"
private const val SPEED_SAMPLE = "888"

private const val LIST_ROW_SHARE = 0.17f
private const val LIST_VALUE_SHARE = 0.5f
private const val LIST_ROW_COUNT = 4
private val ListGutter = 24.dp

private const val PRIMARY_COOLANT_SLOT = 2

private const val TACH_MINOR_STEP_RPM = 500
private const val TACH_MAJOR_STEP_RPM = 1_000
private const val SPEED_MINOR_STEP_KMH = 10
private const val SPEED_MAJOR_STEP_KMH = 20
private const val SPEED_LABEL_STEP_KMH = 40
private val UnitSlotWidth = 48.dp
private val ListRowGap = Spacing.grid

/** What one ring shows: its scale and ticks, where its middle is, whether the rpm zones show on it, and its caption. */
private class RingSpec(
    val scale: ArcScale,
    val centerX: Float,
    val minorStep: Int,
    val majorStep: Int,
    val labelStep: Int,
    val labelDivisor: Int,
    val hasZones: Boolean,
    val caption: String,
)

private fun tachRing(centerX: Float, caption: String) =
    RingSpec(LeftRingTach, centerX, TACH_MINOR_STEP_RPM, TACH_MAJOR_STEP_RPM, TACH_MAJOR_STEP_RPM, TACH_MAJOR_STEP_RPM, hasZones = true, caption = caption)

private fun speedRing(centerX: Float, caption: String, units: UnitSystem) =
    RingSpec(rightRingSpeed(units), centerX, SPEED_MINOR_STEP_KMH, SPEED_MAJOR_STEP_KMH, SPEED_LABEL_STEP_KMH, labelDivisor = 1, hasZones = false, caption = caption)

private class RingStatic(
    size: Size,
    density: Density,
    private val colors: KLineColors,
    numerals: NumeralFace,
    measurer: TextMeasurer,
    private val specs: List<RingSpec>,
) {
    private val layout = RingLayout(size.width, size.height)
    private val unit = layout.radius / RING_REF
    private val numeralStyle = with(density) { numeralStyle(numerals, (NUMERAL_SIZE * unit).toSp()) }
    private val captionStyle = with(density) { numeralStyle(numerals, (layout.radius * CAPTION_SIZE_SHARE).toSp()) }
    private val numeralTexts = specs.map { spec ->
        dialTicks(spec.scale, spec.minorStep, spec.majorStep, spec.labelStep).filter { it.hasLabel }.map { tick ->
            val layoutResult = measurer.measure((tick.value / spec.labelDivisor).toInt().toString(), numeralStyle)
            val middle = polar(spec.centerX, layout.centerY, layout.radius - NUMERAL_RADIUS * unit, tick.angleDeg)
            val capHeight = NUMERAL_SIZE * unit * CAP_HEIGHT_EM
            layoutResult to Offset(middle.x - layoutResult.size.width / 2f, middle.y + capHeight / 2f - layoutResult.firstBaseline)
        }
    }
    private val captions = specs.map { spec ->
        val layoutResult = measurer.measure(spec.caption, captionStyle)
        val baseline = layout.centerY + layout.radius * CAPTION_BASELINE_SHARE
        layoutResult to Offset(spec.centerX - layoutResult.size.width / 2f, baseline - layoutResult.firstBaseline)
    }
    private val ticks = specs.map { dialTicks(it.scale, it.minorStep, it.majorStep, it.labelStep) }

    fun draw(scope: DrawScope) = with(scope) {
        specs.forEachIndexed { index, spec ->
            drawCircle(colors.ring, layout.radius - TRACK_WIDTH * unit / 2f, Offset(spec.centerX, layout.centerY), style = Stroke(TRACK_WIDTH * unit), alpha = TRACK_ALPHA)
            drawGauge(spec)
            drawTicks(spec, ticks[index])
            numeralTexts[index].forEach { (text, at) -> drawText(text, colors.inkMuted, at) }
            drawText(captions[index].first, colors.inkMuted, captions[index].second)
        }
    }

    private fun DrawScope.drawGauge(spec: RingSpec) {
        val stroke = Stroke(GAUGE_WIDTH * unit)
        drawRingArc(layout, unit, spec, spec.scale.min, spec.scale.max, colors.ring, stroke)
        if (!spec.hasZones) return
        drawRingArc(layout, unit, spec, CAUTION_RPM.toDouble(), DANGER_RPM.toDouble(), colors.caution, stroke)
        drawRingArc(layout, unit, spec, DANGER_RPM.toDouble(), spec.scale.max, colors.danger, stroke)
    }

    private fun DrawScope.drawTicks(spec: RingSpec, ticks: List<Tick>) {
        ticks.forEach { tick ->
            val isMajor = tick.kind == TickKind.MAJOR
            val outer = layout.radius - TICK_INSET * unit
            val length = (if (isMajor) MAJOR_TICK_LENGTH else MINOR_TICK_LENGTH) * unit
            drawLine(
                if (isMajor) colors.inkMuted else colors.inkFaint,
                polar(spec.centerX, layout.centerY, outer, tick.angleDeg),
                polar(spec.centerX, layout.centerY, outer - length, tick.angleDeg),
                strokeWidth = (if (isMajor) MAJOR_TICK_WIDTH else MINOR_TICK_WIDTH) * unit,
            )
        }
    }
}

private fun DrawScope.drawRingArc(layout: RingLayout, unit: Float, spec: RingSpec, from: Double, to: Double, color: Color, stroke: Stroke) {
    val start = spec.scale.angleDeg(from)
    val radius = layout.radius - TRACK_WIDTH * unit / 2f
    drawArc(
        color, start, spec.scale.angleDeg(to) - start, false,
        Offset(spec.centerX - radius, layout.centerY - radius), Size(radius * 2, radius * 2),
        style = stroke,
    )
}

private class LitBand(val from: Double, val to: Double, val color: Color)

/**
 * The lit part of a ring up to its value, in the colour of each zone it passes through (the tach
 * only; dimmed when stale), and the marker that crosses the ring at the value.
 */
private class RingLit(size: Size, private val colors: KLineColors, private val specs: List<RingSpec>) {
    private val layout = RingLayout(size.width, size.height)
    private val unit = layout.radius / RING_REF
    private val litStroke = Stroke(LIT_WIDTH * unit)
    private val tachBands = listOf(
        LitBand(LeftRingTach.min, CAUTION_RPM.toDouble(), colors.needle),
        LitBand(CAUTION_RPM.toDouble(), DANGER_RPM.toDouble(), colors.caution),
        LitBand(DANGER_RPM.toDouble(), LeftRingTach.max, colors.danger),
    )

    /** Per ring, made once, so that drawing a frame allocates nothing. */
    private val bandsByRing = specs.map { spec ->
        if (spec.hasZones) tachBands else listOf(LitBand(spec.scale.min, spec.scale.max, colors.needle))
    }

    fun draw(scope: DrawScope, index: Int, value: Float, isStale: Boolean) = with(scope) {
        val spec = specs[index]
        val v = value.toDouble()
        for (band in bandsByRing[index]) {
            if (v <= band.from) continue
            drawRingArc(layout, unit, spec, band.from, v.coerceAtMost(band.to), if (isStale) colors.inkFaint else band.color, litStroke)
        }
        val angle = spec.scale.angleDeg(v)
        val radius = layout.radius - TRACK_WIDTH * unit / 2f
        drawLine(
            colors.ink,
            polar(spec.centerX, layout.centerY, radius - MARKER_INSET * unit, angle),
            polar(spec.centerX, layout.centerY, radius + MARKER_OUTSET * unit, angle),
            strokeWidth = MARKER_WIDTH * unit,
        )
    }
}

/**
 * Two ring gauges cut by the screen edges, a big light numeral in the middle of each, and between
 * them four values as a list: the page's third primary field (the coolant) and its first three
 * small fields.
 */
@Composable
internal fun VirtualCluster(page: MainPage, live: LiveState, modifier: Modifier = Modifier) {
    val rpm = rememberGaugeFeed(live, RPM_ID, glides = true)
    val speed = rememberGaugeFeed(live, SPEED_ID, glides = true)
    val colors = KLineTheme.colors
    val numerals = LocalClusterLook.current.numerals
    val measurer = rememberTextMeasurer()
    val captions = fieldLabel(rpm.signal) to fieldLabel(speed.signal)
    BoxWithConstraints(modifier.clipToBounds()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val layout = remember(widthPx, heightPx) { RingLayout(widthPx, heightPx) }
        val units = LocalUnitSystem.current
        val specs = remember(layout, captions, units) {
            listOf(tachRing(layout.leftCenterX, captions.first), speedRing(layout.rightCenterX, captions.second, units))
        }
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawWithCache {
                    val painter = RingStatic(size, this, colors, numerals, measurer, specs)
                    onDrawBehind { painter.draw(this) }
                },
        )
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val painter = RingLit(size, colors, specs)
                    onDrawBehind {
                        painter.draw(this, 0, rpm.drawnValue(), rpm.stale.value)
                        painter.draw(this, 1, speed.drawnValue(), speed.stale.value)
                    }
                },
        )
        RingReadout(rpm, layout, layout.leftCenterX, RPM_SAMPLE)
        RingReadout(speed, layout, layout.rightCenterX, SPEED_SAMPLE)
        ValueList(listOf(page.primary[PRIMARY_COOLANT_SLOT]) + page.fields.take(LIST_ROW_COUNT - 1), live, layout)
    }
}

/** The numeral of a ring: big and light, centred on the ring's centre. */
@Composable
private fun RingReadout(feed: GaugeFeed, layout: RingLayout, centerX: Float, sample: String) {
    val density = LocalDensity.current
    val colors = KLineTheme.colors
    val numerals = LocalClusterLook.current.numerals
    val fontPx = layout.radius * READOUT_SIZE_SHARE
    val style = remember(numerals, fontPx, density) { numeralStyle(numerals, with(density) { fontPx.toSp() }) }
    val capHeight = (fontPx * CAP_HEIGHT_EM).roundToInt()
    val top = (layout.centerY + layout.radius * READOUT_BASELINE_SHARE).roundToInt() - capHeight
    val diameter = with(density) { (layout.radius * 2).toDp() }
    Box(
        Modifier.offset { IntOffset((centerX - layout.radius).roundToInt(), 0) }.size(diameter),
        contentAlignment = Alignment.TopCenter,
    ) {
        ValueText(
            feed.signal, feed.reading, feed.stale, feed.tone, style,
            Modifier.offset { IntOffset(0, top) }, sample, ink = colors.ink, align = TextAlign.Center,
        )
    }
}

/** Four rows between the rings: label left, value and unit right, a hairline between them. */
@Composable
private fun ValueList(ids: List<String>, live: LiveState, layout: RingLayout) {
    val density = LocalDensity.current
    val known = ids.filter { Signals.byId(it) != null }
    val rowHeight = with(density) { (layout.centerY * 2 * LIST_ROW_SHARE).toDp() }
    val start = with(density) { layout.listStart.toDp() } + ListGutter
    val width = with(density) { (layout.listEnd - layout.listStart).toDp() } - ListGutter * 2
    Column(
        Modifier.offset(x = start).width(width).fillMaxHeight(),
        verticalArrangement = Arrangement.Center,
    ) {
        known.forEachIndexed { index, id ->
            if (index > 0) HorizontalRule()
            ValueRow(id, live, rowHeight)
        }
    }
}

@Composable
private fun ValueRow(id: String, live: LiveState, height: Dp) {
    val feed = rememberGaugeFeed(live, id, glides = false)
    val numerals = LocalClusterLook.current.numerals
    val colors = KLineTheme.colors
    val density = LocalDensity.current
    val style = remember(numerals, height, density) { numeralStyle(numerals, with(density) { (height.toPx() * LIST_VALUE_SHARE).toSp() }) }
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.CenterStart) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ListRowGap)) {
            FieldCaption(fieldLabel(feed.signal), Modifier.weight(1f).alignByBaseline())
            ValueText(feed.signal, feed.reading, feed.stale, feed.tone, style, Modifier.alignByBaseline(), ink = colors.ink)
            if (!labelIsUnit(id)) {
                Text(unitOf(feed.signal, LocalUnitSystem.current), style = KLineType.caption, color = colors.inkMuted, maxLines = 1, modifier = Modifier.width(UnitSlotWidth).alignByBaseline())
            }
        }
    }
}
