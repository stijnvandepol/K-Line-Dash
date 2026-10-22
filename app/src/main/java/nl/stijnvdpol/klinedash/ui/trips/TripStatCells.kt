package nl.stijnvdpol.klinedash.ui.trips

import nl.stijnvdpol.klinedash.core.trip.TripStats
import nl.stijnvdpol.klinedash.data.UnitSystem
import nl.stijnvdpol.klinedash.service.Quantity
import nl.stijnvdpol.klinedash.service.format
import nl.stijnvdpol.klinedash.service.formatNumber
import nl.stijnvdpol.klinedash.service.label
import nl.stijnvdpol.klinedash.ui.MISSING_VALUE
import nl.stijnvdpol.klinedash.ui.formatDuration

private const val PERCENT = 100.0

/*
 * What the statistics tables of the TRIPS page say, as plain functions: the labels, units and values
 * of every cell, so the tests can read them without a screen. The composables only draw the result.
 */

/** One cell of the statistics table: a caps label with its unit, and the value ready to draw. */
internal data class StatCell(val label: String, val unit: String?, val value: String)

private const val DISTANCE_DECIMALS = 1
private const val SPEED_DECIMALS = 0
private const val TEMPERATURE_DECIMALS = 0
private const val FUEL_DECIMALS = 1

/**
 * The statistics of a trip for the table of the detail screen, in reading order, in [units]. [stats]
 * is null for a file without a readable row; every value is then "--". A value that was never
 * measured (no coolant reading, no air flow) is "--" too, never 0. Consumption is litres per 100 km
 * in metric and miles per gallon in imperial, so its label changes too.
 */
internal fun statCells(stats: TripStats?, units: UnitSystem): List<StatCell> = listOf(
    StatCell("Duration", null, stats?.let { formatDuration(it.durationMs) } ?: MISSING_VALUE),
    StatCell("Distance", units.label(Quantity.DISTANCE), number(stats?.distanceKm, Quantity.DISTANCE, DISTANCE_DECIMALS, units)),
    StatCell("Moving time", null, stats?.let { formatDuration(it.movingTimeMs) } ?: MISSING_VALUE),
    StatCell("Avg speed", units.label(Quantity.SPEED), number(stats?.averageSpeedKmh, Quantity.SPEED, SPEED_DECIMALS, units)),
    StatCell("Avg moving", units.label(Quantity.SPEED), number(stats?.averageMovingSpeedKmh, Quantity.SPEED, SPEED_DECIMALS, units)),
    StatCell("Max speed", units.label(Quantity.SPEED), number(stats?.maxSpeedKmh, Quantity.SPEED, SPEED_DECIMALS, units)),
    StatCell("Max RPM", "rpm", plain(stats?.maxRpm, 0)),
    StatCell("Max coolant", units.label(Quantity.TEMPERATURE), number(stats?.maxCoolantC, Quantity.TEMPERATURE, TEMPERATURE_DECIMALS, units)),
    StatCell("Full throttle", "%", plain(stats?.let { fullThrottleShare(it.fullThrottleTimeMs, it.durationMs, hasObdData = it.maxRpm != null) }, 0)),
    StatCell("Fuel (est.)", units.label(Quantity.FUEL_VOLUME), number(stats?.estimatedFuelLiters, Quantity.FUEL_VOLUME, FUEL_DECIMALS, units)),
    when (units) {
        UnitSystem.METRIC -> StatCell("Fuel/100 km (est.)", "L", plain(stats?.estimatedFuelLitersPer100Km, FUEL_DECIMALS))
        UnitSystem.IMPERIAL -> StatCell(
            "Economy (est.)", units.label(Quantity.FUEL_ECONOMY),
            number(stats?.estimatedFuelLitersPer100Km, Quantity.FUEL_ECONOMY, FUEL_DECIMALS, units),
        )
    },
)

/**
 * The six fields of the session panel while a trip records, in reading order (two rows of three
 * on the display): "--" until the first sample, and for what the trip cannot measure (no fuel
 * estimate without air flow).
 */
internal fun liveStatCells(stats: TripStats?, units: UnitSystem): List<StatCell> = listOf(
    StatCell("Time", null, stats?.let { formatDuration(it.durationMs) } ?: MISSING_VALUE),
    StatCell("Distance", units.label(Quantity.DISTANCE), number(stats?.distanceKm, Quantity.DISTANCE, DISTANCE_DECIMALS, units)),
    StatCell("Avg ${units.label(Quantity.SPEED)}", null, number(stats?.averageSpeedKmh, Quantity.SPEED, SPEED_DECIMALS, units)),
    StatCell("Max ${units.label(Quantity.SPEED)}", null, number(stats?.maxSpeedKmh, Quantity.SPEED, SPEED_DECIMALS, units)),
    StatCell("Max RPM", null, plain(stats?.maxRpm, 0)),
    StatCell("Fuel", "${units.label(Quantity.FUEL_VOLUME)} est.", number(stats?.estimatedFuelLiters, Quantity.FUEL_VOLUME, FUEL_DECIMALS, units)),
)

private fun plain(value: Double?, decimals: Int): String = value?.let { formatNumber(it, decimals) } ?: MISSING_VALUE

/** A metric [value] in [units], or "--" when it was never measured. */
private fun number(value: Double?, quantity: Quantity, metricDecimals: Int, units: UnitSystem): String =
    value?.let { units.format(quantity, it, metricDecimals) } ?: MISSING_VALUE

/**
 * The share of [durationMs] (a trip or a lap) with the throttle above 90 %, in percent; null without OBD
 * data (no rpm was read), where 0 would be a lie, and for a duration of 0.
 */
internal fun fullThrottleShare(fullThrottleMs: Long, durationMs: Long, hasObdData: Boolean): Double? =
    if (!hasObdData || durationMs <= 0) null else fullThrottleMs * PERCENT / durationMs
