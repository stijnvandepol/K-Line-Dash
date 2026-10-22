package nl.stijnvdpol.klinedash.ui.cluster

import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/*
 * The pure geometry of the cluster styles (docs/cluster-styles.md): where a value sits on a dial,
 * where the ticks go, how many shift lights burn. Angles are degrees clockwise from 3 o'clock, as
 * on the screen (90 is straight down, 270 straight up). Nothing here draws or touches the clock.
 */

private const val FULL_TURN_DEG = 360f
private const val QUARTER_TURN_DEG = 90f
private const val DEG_TO_RAD = (Math.PI / 180.0).toFloat()

/** The tach and the speedometer scales of docs/cluster-styles.md. */
internal const val TACH_MAX_RPM = 9_000.0
internal const val SPEEDO_MAX_KMH = 260.0

/** Sweep of the round dials of the twin and centre styles, and of the arc style's tachometer. */
internal const val DIAL_SWEEP_DEG = 250f
internal const val ARC_SWEEP_DEG = 200f

/**
 * A scale laid out on an arc: [min] sits at [startDeg], [max] at [startDeg] + [sweepDeg]. A negative
 * sweep runs counter-clockwise (the left ring of the virtual style fills up the inside of the screen).
 */
internal data class ArcScale(val min: Double, val max: Double, val startDeg: Float, val sweepDeg: Float) {
    /** How far along the scale [value] is, 0 to 1; values outside the scale stop at its ends. */
    fun fraction(value: Double): Float = ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)

    fun angleDeg(value: Double): Float = startDeg + sweepDeg * fraction(value)
}

/** A scale of [sweepDeg] centred on 12 o'clock, so the opening of the dial is at the bottom. */
internal fun topCentredScale(min: Double, max: Double, sweepDeg: Float): ArcScale =
    ArcScale(min, max, QUARTER_TURN_DEG + (FULL_TURN_DEG - sweepDeg) / 2f, sweepDeg)

internal enum class TickKind { MINOR, MAJOR }

/** [hasLabel] ticks get a numeral. */
internal data class Tick(val value: Double, val angleDeg: Float, val kind: TickKind, val hasLabel: Boolean)

/**
 * Ticks from the scale's minimum to its maximum, one every [minorStep]; every [majorStep] a major
 * one, every [labelStep] a major one with a numeral. The steps must divide the scale's range; whole
 * numbers keep the count free of float drift.
 */
internal fun dialTicks(scale: ArcScale, minorStep: Int, majorStep: Int, labelStep: Int): List<Tick> {
    val range = (scale.max - scale.min).toInt()
    require(range % minorStep == 0 && majorStep % minorStep == 0 && labelStep % majorStep == 0) {
        "Steps $minorStep/$majorStep/$labelStep do not divide the range $range"
    }
    return (0..range / minorStep).map { index ->
        val offset = index * minorStep
        val isMajor = offset % majorStep == 0
        val value = scale.min + offset
        Tick(value, scale.angleDeg(value), if (isMajor) TickKind.MAJOR else TickKind.MINOR, hasLabel = isMajor && offset % labelStep == 0)
    }
}

/** The point at [radius] from ([centerX], [centerY]) in direction [angleDeg]. */
internal fun polar(centerX: Float, centerY: Float, radius: Float, angleDeg: Float): Offset {
    val radians = angleDeg * DEG_TO_RAD
    return Offset(centerX + radius * cos(radians), centerY + radius * sin(radians))
}

/** Start and sweep of segment [index] of [count] on [scale], with [gapDeg] left open between neighbours. */
internal fun segmentArc(scale: ArcScale, index: Int, count: Int, gapDeg: Float): Pair<Float, Float> {
    val pitch = scale.sweepDeg / count
    return (scale.startDeg + index * pitch + gapDeg / 2f) to (pitch - gapDeg)
}

internal const val SHIFT_LIGHT_COUNT = 10

/** The lights come on over this many rpm before the shift point. */
internal const val SHIFT_LIGHT_SPAN_RPM = 1_500

private const val GREEN_LIGHTS = 4
private const val AMBER_LIGHTS = 3

internal enum class ShiftLightColour { GREEN, AMBER, RED }

/** Green first, then amber, then red: 4, 3 and 3 of the ten lights. */
internal fun shiftLightColour(index: Int): ShiftLightColour = when {
    index < GREEN_LIGHTS -> ShiftLightColour.GREEN
    index < GREEN_LIGHTS + AMBER_LIGHTS -> ShiftLightColour.AMBER
    else -> ShiftLightColour.RED
}

/**
 * How many of the [SHIFT_LIGHT_COUNT] lights burn at [rpm]: the first at [SHIFT_LIGHT_SPAN_RPM]
 * below [shiftRpm], one more every equal step, all of them at the shift point itself.
 */
internal fun shiftLightCount(rpm: Double, shiftRpm: Int): Int {
    val first = shiftRpm - SHIFT_LIGHT_SPAN_RPM
    if (rpm < first) return 0
    val step = SHIFT_LIGHT_SPAN_RPM.toDouble() / (SHIFT_LIGHT_COUNT - 1)
    return (floor((rpm - first) / step).toInt() + 1).coerceIn(0, SHIFT_LIGHT_COUNT)
}

private const val TWIN_DIAL_HEIGHT_SHARE = 0.92f
private const val TWIN_COOLANT_COLUMN_SHARE = 0.52f
private const val CENTRE_DIAL_HEIGHT_SHARE = 0.96f
private const val CENTRE_DIAL_WIDTH_SHARE = 0.46f

/** Diameter of the two dials of the twin style: as tall as the area allows, but the two dials and the coolant column must fit its width. */
internal fun twinDialSize(width: Float, height: Float, edgeMargin: Float): Float =
    min(height * TWIN_DIAL_HEIGHT_SHARE, (width - 2 * edgeMargin) / (2 + TWIN_COOLANT_COLUMN_SHARE))

/** Width of the coolant column between the twin dials, for dials of [dialSize]. */
internal fun twinCoolantColumn(dialSize: Float): Float = dialSize * TWIN_COOLANT_COLUMN_SHARE

/** Diameter of the one dial of the centre style (about 520 dp on a 1280 x 544 area). */
internal fun centreDialSize(width: Float, height: Float): Float = min(height * CENTRE_DIAL_HEIGHT_SHARE, width * CENTRE_DIAL_WIDTH_SHARE)

private const val ARC_LIGHTS_TOP_SHARE = 0.035f
private const val ARC_LIGHTS_HEIGHT_SHARE = 0.05f
private const val ARC_LIGHTS_GAP_SHARE = 0.03f
private const val ARC_BOTTOM_MARGIN_SHARE = 0.01f
private const val ARC_EDGE_DEG = 10f

/**
 * Where the parts of the arc style sit in an area of [width] by [height] pixels: the strip of shift
 * lights across the top, and the arc below it, as big as fits (the 200 degree arc is a little more
 * than half a circle, so it is about as tall as 1.17 radii).
 */
internal class ArcLayout(width: Float, height: Float) {
    val lightsTop = height * ARC_LIGHTS_TOP_SHARE
    val lightsHeight = height * ARC_LIGHTS_HEIGHT_SHARE
    val centerX = width / 2f
    val radius: Float
    val centerY: Float

    /** The width free on each side of the arc, where the coolant and the battery stand. */
    val sideRoom: Float

    init {
        val arcTop = lightsTop + lightsHeight + height * ARC_LIGHTS_GAP_SHARE
        val room = height - arcTop - height * ARC_BOTTOM_MARGIN_SHARE
        radius = min(room / (1f + sin(ARC_EDGE_DEG * DEG_TO_RAD)), width / 2f)
        centerY = arcTop + radius
        sideRoom = centerX - radius * cos(ARC_EDGE_DEG * DEG_TO_RAD)
    }
}

private const val RING_CENTRE_SHARE = 0.64f
private const val RING_RADIUS_HEIGHT_SHARE = 0.49f

/**
 * The two ring gauges of the virtual style in an area of [width] by [height] pixels. Each ring is
 * centred [RING_CENTRE_SHARE] of its radius in from its screen edge, so the edge cuts off a good
 * part of it; the numerals sit in the centre.
 */
internal class RingLayout(width: Float, height: Float) {
    val radius = height * RING_RADIUS_HEIGHT_SHARE
    val centerY = height / 2f
    val leftCenterX = radius * RING_CENTRE_SHARE
    val rightCenterX = width - leftCenterX

    /** Where the middle list may start and end: between the two rings. */
    val listStart = leftCenterX + radius
    val listEnd = rightCenterX - radius
}

private const val RING_SWEEP_DEG = 236f
private const val RING_START_DEG = 118f

/** The tach ring: the left one, filling from the bottom up along its inside (counter-clockwise). */
internal val LeftRingTach = ArcScale(0.0, TACH_MAX_RPM, RING_START_DEG, -RING_SWEEP_DEG)

/** The speed ring: the right one, the mirror image of [LeftRingTach]. */
internal val RightRingSpeed = ArcScale(0.0, SPEEDO_MAX_KMH, FULL_TURN_DEG / 2 - RING_START_DEG, RING_SWEEP_DEG)

/** The round tachometer of the twin and centre styles. */
internal val DialTach = topCentredScale(0.0, TACH_MAX_RPM, DIAL_SWEEP_DEG)

/** The round speedometer of the twin style. */
internal val DialSpeedo = topCentredScale(0.0, SPEEDO_MAX_KMH, DIAL_SWEEP_DEG)

/** The segmented tachometer of the arc style. */
internal val ArcTach = topCentredScale(0.0, TACH_MAX_RPM, ARC_SWEEP_DEG)

/** The slim coolant dial between the twin dials: a quarter of a circle across the top, cold left, hot right. */
internal val CoolantScale = ArcScale(40.0, 130.0, 225f, QUARTER_TURN_DEG)
