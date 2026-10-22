package nl.stijnvdpol.klinedash.ui.trips

import nl.stijnvdpol.klinedash.core.trip.TripStats
import nl.stijnvdpol.klinedash.service.formatNumber
import nl.stijnvdpol.klinedash.ui.MISSING_VALUE
import nl.stijnvdpol.klinedash.ui.formatDuration

private const val PERCENT = 100.0

/** One cell of the statistics table: a caps label with its unit, and the value ready to draw. */
internal data class StatCell(val label: String, val unit: String?, val value: String)

/**
 * The statistics of a trip for the table of the detail screen, in reading order. [stats] is null for
 * a file without a readable row; every value is then "--". A value that was never measured (no
 * coolant reading, no air flow) is "--" too, never 0.
 */
internal fun statCells(stats: TripStats?): List<StatCell> = listOf(
    StatCell("Duration", null, stats?.let { formatDuration(it.durationMs) } ?: MISSING_VALUE),
    StatCell("Distance", "km", number(stats?.distanceKm, 1)),
    StatCell("Moving time", null, stats?.let { formatDuration(it.movingTimeMs) } ?: MISSING_VALUE),
    StatCell("Avg speed", "km/h", number(stats?.averageSpeedKmh, 0)),
    StatCell("Avg moving", "km/h", number(stats?.averageMovingSpeedKmh, 0)),
    StatCell("Max speed", "km/h", number(stats?.maxSpeedKmh, 0)),
    StatCell("Max RPM", "rpm", number(stats?.maxRpm, 0)),
    StatCell("Max coolant", "°C", number(stats?.maxCoolantC, 0)),
    StatCell("Full throttle", "%", number(stats?.let(::fullThrottleShare), 0)),
    StatCell("Fuel (est.)", "L", number(stats?.estimatedFuelLiters, 1)),
    StatCell("Fuel/100 km (est.)", "L", number(stats?.estimatedFuelLitersPer100Km, 1)),
)

/**
 * The six fields of the session panel while a trip records, in reading order (two rows of three
 * on the display): "--" until the first sample, and for what the trip cannot measure (no fuel
 * estimate without air flow).
 */
internal fun liveStatCells(stats: TripStats?): List<StatCell> = listOf(
    StatCell("Time", null, stats?.let { formatDuration(it.durationMs) } ?: MISSING_VALUE),
    StatCell("Distance", "km", number(stats?.distanceKm, 1)),
    StatCell("Avg km/h", null, number(stats?.averageSpeedKmh, 0)),
    StatCell("Max km/h", null, number(stats?.maxSpeedKmh, 0)),
    StatCell("Max RPM", null, number(stats?.maxRpm, 0)),
    StatCell("Fuel", "L est.", number(stats?.estimatedFuelLiters, 1)),
)

private fun number(value: Double?, decimals: Int): String = value?.let { formatNumber(it, decimals) } ?: MISSING_VALUE

/** Share of the trip with the throttle above 90 %; null without OBD data (no rpm), where 0 would be a lie. */
private fun fullThrottleShare(stats: TripStats): Double? =
    if (stats.maxRpm == null || stats.durationMs <= 0) null else stats.fullThrottleTimeMs * PERCENT / stats.durationMs
