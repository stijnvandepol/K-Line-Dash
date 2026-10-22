package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.core.alert.AlertKind
import nl.stijnvdpol.klinedash.core.alert.AlertSettings
import nl.stijnvdpol.klinedash.data.UnitSystem
import nl.stijnvdpol.klinedash.service.Quantity
import nl.stijnvdpol.klinedash.service.decimals
import nl.stijnvdpol.klinedash.service.fromMetric
import nl.stijnvdpol.klinedash.service.label
import nl.stijnvdpol.klinedash.service.toMetric
import kotlin.math.pow
import kotlin.math.roundToLong

/*
 * The limits behind the steppers of SETUP > Warnings: how far each one may move and how a step
 * changes the settings. Plain functions, so the rules (ranges, caution below danger, no float
 * drift) are tested without a screen. The limits are stored in metric; the user sees and steps
 * them in the chosen unit system (1 °F or 1 °C a step), and the stored value follows.
 */

private const val COOLANT_MIN_C = 60.0
private const val COOLANT_MAX_C = 130.0
private const val COOLANT_STEP_C = 1.0
private const val VOLTAGE_STEP_V = 0.1

private const val VOLTAGE_RUNNING_LOW_MIN_V = 11.0
private const val VOLTAGE_RUNNING_LOW_MAX_V = 14.0
private const val VOLTAGE_RUNNING_HIGH_MIN_V = 14.0
private const val VOLTAGE_RUNNING_HIGH_MAX_V = 16.5
private const val VOLTAGE_ENGINE_OFF_LOW_MIN_V = 10.0
private const val VOLTAGE_ENGINE_OFF_LOW_MAX_V = 12.8

private const val COOLANT_DECIMALS = 0
private const val VOLTAGE_DECIMALS = 1
private const val DECIMAL_BASE = 10.0

private const val UNIT_VOLT = "V"

/**
 * The five numbers a user can change. [min] and [max] are metric; [step] is one step in the unit the
 * user sees (1 °C, 1 °F or 0.1 V). [quantity] says how the limit is converted.
 */
internal enum class AlertLimit(
    val min: Double,
    val max: Double,
    val step: Double,
    val decimals: Int,
    /** The unit of a limit that does not change with the unit system (volts). */
    private val fixedUnit: String,
    /** The warning this limit belongs to; the stepper is dimmed while that warning is off. */
    val kind: AlertKind,
    /** What the limit measures when its unit depends on the unit system; null for volts. */
    val quantity: Quantity? = null,
) {
    COOLANT_CAUTION(COOLANT_MIN_C, COOLANT_MAX_C, COOLANT_STEP_C, COOLANT_DECIMALS, "", AlertKind.COOLANT_HIGH, Quantity.TEMPERATURE),
    COOLANT_DANGER(COOLANT_MIN_C, COOLANT_MAX_C, COOLANT_STEP_C, COOLANT_DECIMALS, "", AlertKind.COOLANT_HIGH, Quantity.TEMPERATURE),
    VOLTAGE_RUNNING_LOW(
        VOLTAGE_RUNNING_LOW_MIN_V, VOLTAGE_RUNNING_LOW_MAX_V, VOLTAGE_STEP_V, VOLTAGE_DECIMALS, UNIT_VOLT, AlertKind.VOLTAGE_LOW,
    ),
    VOLTAGE_ENGINE_OFF_LOW(
        VOLTAGE_ENGINE_OFF_LOW_MIN_V, VOLTAGE_ENGINE_OFF_LOW_MAX_V, VOLTAGE_STEP_V, VOLTAGE_DECIMALS, UNIT_VOLT, AlertKind.VOLTAGE_LOW,
    ),
    VOLTAGE_RUNNING_HIGH(
        VOLTAGE_RUNNING_HIGH_MIN_V, VOLTAGE_RUNNING_HIGH_MAX_V, VOLTAGE_STEP_V, VOLTAGE_DECIMALS, UNIT_VOLT, AlertKind.VOLTAGE_HIGH,
    ),
    ;

    /** The unit next to the value in [units]. */
    fun unitIn(units: UnitSystem): String = quantity?.let(units::label) ?: fixedUnit

    /** Digits shown after the point in [units]. */
    fun decimalsIn(units: UnitSystem): Int = quantity?.let { units.decimals(it, decimals) } ?: decimals
}

internal fun AlertSettings.valueOf(limit: AlertLimit): Double = when (limit) {
    AlertLimit.COOLANT_CAUTION -> coolantCautionC
    AlertLimit.COOLANT_DANGER -> coolantDangerC
    AlertLimit.VOLTAGE_RUNNING_LOW -> voltageRunningLow
    AlertLimit.VOLTAGE_ENGINE_OFF_LOW -> voltageEngineOffLow
    AlertLimit.VOLTAGE_RUNNING_HIGH -> voltageRunningHigh
}

private fun AlertSettings.withLimit(limit: AlertLimit, value: Double): AlertSettings = when (limit) {
    AlertLimit.COOLANT_CAUTION -> copy(coolantCautionC = value)
    AlertLimit.COOLANT_DANGER -> copy(coolantDangerC = value)
    AlertLimit.VOLTAGE_RUNNING_LOW -> copy(voltageRunningLow = value)
    AlertLimit.VOLTAGE_ENGINE_OFF_LOW -> copy(voltageEngineOffLow = value)
    AlertLimit.VOLTAGE_RUNNING_HIGH -> copy(voltageRunningHigh = value)
}

/** [value] (metric) in the unit the user sees, rounded to the digits the stepper shows. */
private fun AlertLimit.shown(value: Double, units: UnitSystem): Double {
    val inUnits = quantity?.let { units.fromMetric(it, value) } ?: value
    return roundTo(inUnits, decimalsIn(units))
}

/** What the stepper of [limit] shows: the stored value in [units], rounded like the display. */
internal fun AlertSettings.shownValue(limit: AlertLimit, units: UnitSystem): Double = limit.shown(valueOf(limit), units)

/**
 * Caution may not reach danger and danger may not drop to caution: one step apart at the closest.
 * In the shown unit, so the gap is a whole step the user sees.
 */
private fun AlertSettings.allowedShownRange(limit: AlertLimit, units: UnitSystem): ClosedFloatingPointRange<Double> {
    val min = limit.shown(limit.min, units)
    val max = limit.shown(limit.max, units)
    return when (limit) {
        AlertLimit.COOLANT_CAUTION -> min..(shownValue(AlertLimit.COOLANT_DANGER, units) - limit.step).coerceAtLeast(min)
        AlertLimit.COOLANT_DANGER -> (shownValue(AlertLimit.COOLANT_CAUTION, units) + limit.step).coerceAtMost(max)..max
        else -> min..max
    }
}

/** Rounded to the decimals of the limit, so repeated 0.1 V steps never show 13.200000000000001. */
private fun roundTo(value: Double, decimals: Int): Double {
    val scale = DECIMAL_BASE.pow(decimals)
    return (value * scale).roundToLong() / scale
}

/**
 * One step up ([direction] 1) or down (-1) for [limit] as the user sees it in [units] (one step is
 * 1 °C or 1 °F, 0.1 V), kept inside its range; unchanged at the end stop. The stored value stays metric.
 */
internal fun AlertSettings.stepped(limit: AlertLimit, direction: Int, units: UnitSystem): AlertSettings {
    val current = shownValue(limit, units)
    val next = roundTo(current + direction * limit.step, limit.decimalsIn(units)).coerceIn(allowedShownRange(limit, units))
    // Converting the unchanged end value back would only add rounding noise to the stored value.
    if (next == current) return this
    return withLimit(limit, limit.quantity?.let { units.toMetric(it, next) } ?: next)
}

/** False at the end stops, where the stepper cell is dimmed. */
internal fun AlertSettings.canStep(limit: AlertLimit, direction: Int, units: UnitSystem): Boolean = stepped(limit, direction, units) != this
