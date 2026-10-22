package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.core.alert.AlertKind
import nl.stijnvdpol.klinedash.core.alert.AlertSettings
import kotlin.math.pow
import kotlin.math.roundToLong

/*
 * The limits behind the steppers of SETUP > Warnings: how far each one may move and how a step
 * changes the settings. Plain functions, so the rules (ranges, caution below danger, no float
 * drift) are tested without a screen.
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

private const val UNIT_CELSIUS = "°C"
private const val UNIT_VOLT = "V"

/** The five numbers a user can change. */
internal enum class AlertLimit(
    val min: Double,
    val max: Double,
    val step: Double,
    val decimals: Int,
    val unit: String,
    /** The warning this limit belongs to; the stepper is dimmed while that warning is off. */
    val kind: AlertKind,
) {
    COOLANT_CAUTION(COOLANT_MIN_C, COOLANT_MAX_C, COOLANT_STEP_C, COOLANT_DECIMALS, UNIT_CELSIUS, AlertKind.COOLANT_HIGH),
    COOLANT_DANGER(COOLANT_MIN_C, COOLANT_MAX_C, COOLANT_STEP_C, COOLANT_DECIMALS, UNIT_CELSIUS, AlertKind.COOLANT_HIGH),
    VOLTAGE_RUNNING_LOW(
        VOLTAGE_RUNNING_LOW_MIN_V, VOLTAGE_RUNNING_LOW_MAX_V, VOLTAGE_STEP_V, VOLTAGE_DECIMALS, UNIT_VOLT, AlertKind.VOLTAGE_LOW,
    ),
    VOLTAGE_ENGINE_OFF_LOW(
        VOLTAGE_ENGINE_OFF_LOW_MIN_V, VOLTAGE_ENGINE_OFF_LOW_MAX_V, VOLTAGE_STEP_V, VOLTAGE_DECIMALS, UNIT_VOLT, AlertKind.VOLTAGE_LOW,
    ),
    VOLTAGE_RUNNING_HIGH(
        VOLTAGE_RUNNING_HIGH_MIN_V, VOLTAGE_RUNNING_HIGH_MAX_V, VOLTAGE_STEP_V, VOLTAGE_DECIMALS, UNIT_VOLT, AlertKind.VOLTAGE_HIGH,
    ),
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

/** Caution may not reach danger and danger may not drop to caution: one step apart at the closest. */
private fun AlertSettings.allowedRange(limit: AlertLimit): ClosedFloatingPointRange<Double> = when (limit) {
    AlertLimit.COOLANT_CAUTION -> limit.min..(coolantDangerC - limit.step).coerceAtLeast(limit.min)
    AlertLimit.COOLANT_DANGER -> (coolantCautionC + limit.step).coerceAtMost(limit.max)..limit.max
    else -> limit.min..limit.max
}

/** Rounded to the decimals of the limit, so repeated 0.1 V steps never show 13.200000000000001. */
private fun roundTo(value: Double, decimals: Int): Double {
    val scale = DECIMAL_BASE.pow(decimals)
    return (value * scale).roundToLong() / scale
}

/** One step up ([direction] 1) or down (-1) for [limit], kept inside its range; unchanged at the end stop. */
internal fun AlertSettings.stepped(limit: AlertLimit, direction: Int): AlertSettings {
    val range = allowedRange(limit)
    val next = roundTo(valueOf(limit) + direction * limit.step, limit.decimals).coerceIn(range)
    return withLimit(limit, next)
}

/** False at the end stops, where the stepper cell is dimmed. */
internal fun AlertSettings.canStep(limit: AlertLimit, direction: Int): Boolean = stepped(limit, direction) != this
