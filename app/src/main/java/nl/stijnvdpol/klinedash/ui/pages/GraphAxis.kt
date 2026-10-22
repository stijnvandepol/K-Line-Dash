package nl.stijnvdpol.klinedash.ui.pages

import nl.stijnvdpol.klinedash.service.SignalDisplay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * A graph never spans less than this share of its signal's display range ([SignalDisplay.min] to
 * [SignalDisplay.max]), so coolant moving between 85 and 87 °C reads as the small change it is.
 */
internal const val MIN_AXIS_SPAN_SHARE = 0.15

/** The axis is divided into about this many steps; both ends are whole steps, so the labels are round. */
private const val TARGET_STEPS = 5

/** Step sizes are 1, 2 or 5 times a power of ten. */
private val NiceMantissas = doubleArrayOf(1.0, 2.0, 5.0, 10.0)

/** Absorbs floating point noise such as 13.3 / 0.2 = 66.49999 before rounding to whole steps. */
private const val STEP_EPSILON = 1e-9

/**
 * The value axis of a graph cell: the bottom and the top of the plot, which are also its two labels.
 * [data] and the axis are in the unit the signal is shown in ([signal]).
 *
 * - At least [MIN_AXIS_SPAN_SHARE] of the signal's display range (and one display step) high,
 *   centred on [data], so noise on a steady signal does not fill the cell.
 * - Moved inside the display range while the data is inside it, so a speed of 0 gets 0 at the
 *   bottom instead of a negative axis.
 * - Widened to whole multiples of a 1-2-5 step: the labels read "78" and "94", not "79.25".
 * - Never past an end of the display range that the data stays within ("9000", not "10000").
 */
internal fun graphAxis(data: ValueRange, signal: SignalDisplay): ValueRange {
    val displayStep = 10.0.pow(-signal.decimals)
    val minSpan = maxOf((signal.max - signal.min) * MIN_AXIS_SPAN_SHARE, displayStep)
    val isLowInside = data.low >= signal.min
    val isHighInside = data.high <= signal.max
    var low = data.low
    var high = data.high
    if (high - low < minSpan) {
        val middle = (low + high) / 2
        low = middle - minSpan / 2
        high = middle + minSpan / 2
        if (isLowInside && low < signal.min) {
            high += signal.min - low
            low = signal.min
        }
        if (isHighInside && high > signal.max) {
            low -= high - signal.max
            high = signal.max
        }
    }
    val step = maxOf(niceStep((high - low) / TARGET_STEPS), displayStep)
    var axisLow = floor(low / step + STEP_EPSILON) * step
    var axisHigh = ceil(high / step - STEP_EPSILON) * step
    if (isLowInside) axisLow = maxOf(axisLow, signal.min)
    if (isHighInside) axisHigh = minOf(axisHigh, signal.max)
    return ValueRange(axisLow, axisHigh)
}

/** The 1-2-5 step closest to [raw] on a log scale; [raw] must be above zero. */
internal fun niceStep(raw: Double): Double {
    val magnitude = 10.0.pow(floor(log10(raw)))
    val mantissa = raw / magnitude
    val nearest = NiceMantissas.minBy { abs(log10(it / mantissa)) }
    return nearest * magnitude
}
