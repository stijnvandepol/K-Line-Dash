package nl.stijnvdpol.klinedash.core.trip

import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.obd.Reading

/**
 * Folds trip samples into a [TripSummary]. Feed every sample that goes into the CSV through [add],
 * in time order; [summary] can be read at any moment. It is the live-recording face of
 * [TripStatsAccumulator], which holds the rules: distance from GPS where a fresh fix exists at both
 * ends of an interval and from the trapezoid of the two fresh vehicle speeds elsewhere, nothing
 * across an interval longer than [maxGapMs], and nothing where a speed is missing or stale because
 * an unknown speed is not zero.
 */
class TripSummarizer(
    private val freshnessMs: Long = DEFAULT_TRIP_FRESHNESS_MS,
    maxGapMs: Long = TripStatsAccumulator.DEFAULT_MAX_GAP_MS,
) {
    private val accumulator = TripStatsAccumulator(maxGapMs)

    fun add(nowMs: Long, readings: Map<String, Reading>, gps: GeoFix? = null) {
        accumulator.add(tripSampleOf(nowMs, readings, gps, freshnessMs))
    }

    /** Null until the first sample has been added. */
    fun summary(): TripSummary? {
        val stats = accumulator.stats() ?: return null
        return TripSummary(
            startMs = stats.startMs,
            endMs = stats.endMs,
            sampleCount = stats.sampleCount,
            distanceKm = stats.distanceKm,
            maxRpm = stats.maxRpm,
            maxSpeedKmh = stats.maxSpeedKmh,
            maxCoolantC = stats.maxCoolantC,
        )
    }

    companion object {
        /** Samples come about once a second; a longer silence is a pause, not driving. */
        const val DEFAULT_MAX_GAP_MS = TripStatsAccumulator.DEFAULT_MAX_GAP_MS
    }
}
