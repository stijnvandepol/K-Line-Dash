package nl.stijnvdpol.klinedash.core.trip

import nl.stijnvdpol.klinedash.core.obd.Reading

/**
 * Folds trip samples into a [TripSummary]. Feed every sample that goes into the CSV through [add],
 * in time order; [summary] can be read at any moment.
 *
 * Distance integrates the speed over the time between two samples (trapezoid of the two fresh
 * speeds). An interval longer than [maxGapMs] adds nothing: a pause (app in the background, link
 * down) must not count as driving at the last known speed. An interval where either speed is
 * missing or stale adds nothing either, because an unknown speed is not zero.
 */
class TripSummarizer(
    private val freshnessMs: Long = DEFAULT_TRIP_FRESHNESS_MS,
    private val maxGapMs: Long = DEFAULT_MAX_GAP_MS,
) {
    private var firstSampleMs: Long? = null
    private var lastSampleMs = 0L
    private var lastSpeedKmh: Double? = null
    private var sampleCount = 0
    private var distanceKm = 0.0
    private var maxRpm: Double? = null
    private var maxSpeedKmh: Double? = null
    private var maxCoolantC: Double? = null

    fun add(nowMs: Long, readings: Map<String, Reading>) {
        val speedKmh = readings.freshValue(SIGNAL_SPEED, nowMs, freshnessMs)
        if (firstSampleMs == null) firstSampleMs = nowMs else addDistance(nowMs, speedKmh)
        lastSampleMs = nowMs
        lastSpeedKmh = speedKmh
        sampleCount++
        maxSpeedKmh = maxOfNullable(maxSpeedKmh, speedKmh)
        maxRpm = maxOfNullable(maxRpm, readings.freshValue(SIGNAL_RPM, nowMs, freshnessMs))
        maxCoolantC = maxOfNullable(maxCoolantC, readings.freshValue(SIGNAL_COOLANT, nowMs, freshnessMs))
    }

    /** Null until the first sample has been added. */
    fun summary(): TripSummary? {
        val startMs = firstSampleMs ?: return null
        return TripSummary(startMs, lastSampleMs, sampleCount, distanceKm, maxRpm, maxSpeedKmh, maxCoolantC)
    }

    private fun addDistance(nowMs: Long, speedKmh: Double?) {
        val intervalMs = nowMs - lastSampleMs
        val previousSpeedKmh = lastSpeedKmh
        if (intervalMs <= 0 || intervalMs > maxGapMs) return
        if (previousSpeedKmh == null || speedKmh == null) return
        val averageKmh = (previousSpeedKmh + speedKmh) / 2.0
        distanceKm += averageKmh * intervalMs / MS_PER_HOUR
    }

    private fun maxOfNullable(current: Double?, candidate: Double?): Double? =
        if (candidate == null) current else maxOf(current ?: candidate, candidate)

    companion object {
        /** Samples come about once a second; a longer silence is a pause, not driving. */
        const val DEFAULT_MAX_GAP_MS = 10_000L

        private const val MS_PER_HOUR = 3_600_000.0
        private const val SIGNAL_SPEED = "speed"
        private const val SIGNAL_RPM = "rpm"
        private const val SIGNAL_COOLANT = "coolant"
    }
}
