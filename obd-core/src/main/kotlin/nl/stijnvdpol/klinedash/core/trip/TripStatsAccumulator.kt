package nl.stijnvdpol.klinedash.core.trip

import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.geo.Geodesy
import nl.stijnvdpol.klinedash.core.geo.GpsSegment
import nl.stijnvdpol.klinedash.core.geo.KMH_PER_MPS
import nl.stijnvdpol.klinedash.core.obd.SignalIds

/**
 * What one sample says about the car, with GPS speed preferred over vehicle speed. Only a fix that
 * is fresh and accurate enough ([GpsSegment.isAccurate]) is used; any other counts as no fix.
 */
private class SampleMetrics(sample: TripSample) {
    val timeMs = sample.timeMs
    val obdSpeedKmh: Double? = sample.values[SignalIds.SPEED]
    val usableFix: GeoFix? = sample.gps?.takeIf { sample.timeMs - it.timeMs <= FRESH_FIX_AGE_MS && GpsSegment.isAccurate(it) }
    val hasFreshFix: Boolean = usableFix != null
    val speedKmh: Double? = usableFix?.speedMps?.times(KMH_PER_MPS) ?: obdSpeedKmh
    val rpm: Double? = sample.values[SignalIds.RPM]
    val throttlePercent: Double? = sample.values[SignalIds.THROTTLE]
    val airFlowGramsPerSecond: Double? = sample.values[SignalIds.MAF]
}

/** A GPS fix pair further apart in time than this is a dropout, not driving (research: 3 s). */
private const val MAX_FIX_GAP_MS = 3_000L

/** A fix older than this at sample time no longer stands for the car's position or speed. */
private const val FRESH_FIX_AGE_MS = 2_000L
private const val MS_PER_HOUR = 3_600_000.0
private const val MOVING_SPEED_KMH = 2.0
private const val STANDSTILL_SPEED_MPS = 0.5
private const val ENGINE_RUNNING_RPM = 300.0
private const val FULL_THROTTLE_PERCENT = 90.0
private const val METRES_PER_KM = 1_000.0

/**
 * Fuel in litres per hour per gram of air per second: air mass / 14.7 (stoichiometric petrol) /
 * 745 g/L (petrol density), times 3600 s/h. About 0.329.
 */
private const val FUEL_LITERS_PER_HOUR_PER_MAF = 3_600.0 / (14.7 * 745.0)

/**
 * Folds [TripSample]s into [TripStats]: feed them in time order with [add], read [stats] at any
 * moment. The same code serves a live trip and a recorded CSV.
 *
 * Time-based totals (moving, idle, full throttle, fuel) hold each sample's state until the next
 * sample. An interval longer than [maxGapMs] adds nothing (a pause is not driving). Distance sums
 * GPS fix-to-fix distances, ignoring movement while the Doppler speed says standstill (GPS drifts
 * by metres when parked), and falls back to the trapezoid of vehicle speed for intervals without
 * a usable fix (at most 2 s old, accurate enough) at both ends. The up to 2 s before a GPS dropout
 * is therefore counted by neither source, and a 1 Hz fix arriving late may be counted by both;
 * both are small.
 *
 * A GPS segment ends, without adding distance, where the fixes are more than 3 s apart, where time
 * runs backwards by more than that (another clock), where the step between two fixes is not
 * plausible ([GpsSegment.isPlausible]: a jump) and at [startNewGpsSegment] (the location source
 * changed). The next fix then starts a new segment, so one bad fix never adds distance.
 */
class TripStatsAccumulator(private val maxGapMs: Long = DEFAULT_MAX_GAP_MS) {
    private var firstMs: Long? = null
    private var previous: SampleMetrics? = null
    private var lastFix: GeoFix? = null
    private var sampleCount = 0
    private var gpsKm = 0.0
    private var obdKm = 0.0
    private var movingMs = 0L
    private var idleMs = 0L
    private var fullThrottleMs = 0L
    private var fuelLiters: Double? = null
    private var maxSpeedKmh: Double? = null
    private var minSpeedKmh: Double? = null
    private var maxRpm: Double? = null
    private var maxCoolantC: Double? = null

    fun add(sample: TripSample) {
        val metrics = SampleMetrics(sample)
        if (firstMs == null) firstMs = sample.timeMs
        previous?.let { addInterval(it, metrics) }
        metrics.usableFix?.let { addFix(it, metrics.obdSpeedKmh?.div(KMH_PER_MPS)) }
        previous = metrics
        sampleCount++
        maxSpeedKmh = maxOfNullable(maxSpeedKmh, metrics.speedKmh)
        minSpeedKmh = minOfNullable(minSpeedKmh, metrics.speedKmh)
        maxRpm = maxOfNullable(maxRpm, metrics.rpm)
        maxCoolantC = maxOfNullable(maxCoolantC, sample.values[SignalIds.COOLANT])
    }

    /** The next fix starts a new GPS segment: nothing is added between the last fix and it. */
    fun startNewGpsSegment() {
        lastFix = null
    }

    /** Null until the first sample has been added. */
    fun stats(): TripStats? {
        val startMs = firstMs ?: return null
        val endMs = previous?.timeMs ?: return null
        return TripStats(
            startMs = startMs,
            endMs = endMs,
            sampleCount = sampleCount,
            distanceKm = gpsKm + obdKm,
            gpsDistanceKm = gpsKm,
            movingTimeMs = movingMs,
            idleTimeMs = idleMs,
            maxSpeedKmh = maxSpeedKmh,
            minSpeedKmh = minSpeedKmh,
            maxRpm = maxRpm,
            maxCoolantC = maxCoolantC,
            fullThrottleTimeMs = fullThrottleMs,
            estimatedFuelLiters = fuelLiters,
        )
    }

    private fun addInterval(from: SampleMetrics, to: SampleMetrics) {
        val intervalMs = to.timeMs - from.timeMs
        if (intervalMs <= 0 || intervalMs > maxGapMs) return
        if (!(from.hasFreshFix && to.hasFreshFix)) addObdDistance(from, to, intervalMs)
        addTimeInState(from, intervalMs)
        addFuel(from, to, intervalMs)
    }

    private fun addObdDistance(from: SampleMetrics, to: SampleMetrics, intervalMs: Long) {
        val fromKmh = from.obdSpeedKmh ?: return
        val toKmh = to.obdSpeedKmh ?: return
        obdKm += (fromKmh + toKmh) / 2.0 * intervalMs / MS_PER_HOUR
    }

    private fun addTimeInState(state: SampleMetrics, intervalMs: Long) {
        val speedKmh = state.speedKmh
        if (speedKmh != null) {
            if (speedKmh >= MOVING_SPEED_KMH) {
                movingMs += intervalMs
            } else if ((state.rpm ?: 0.0) > ENGINE_RUNNING_RPM) {
                idleMs += intervalMs
            }
        }
        if ((state.throttlePercent ?: 0.0) > FULL_THROTTLE_PERCENT) fullThrottleMs += intervalMs
    }

    private fun addFuel(from: SampleMetrics, to: SampleMetrics, intervalMs: Long) {
        val fromFlow = from.airFlowGramsPerSecond ?: return
        val toFlow = to.airFlowGramsPerSecond ?: return
        val litersPerHour = (fromFlow + toFlow) / 2.0 * FUEL_LITERS_PER_HOUR_PER_MAF
        fuelLiters = (fuelLiters ?: 0.0) + litersPerHour * intervalMs / MS_PER_HOUR
    }

    private fun addFix(fix: GeoFix, obdSpeedMps: Double?) {
        val last = lastFix
        // The same fix repeats in consecutive samples; one slightly out of order is dropped.
        if (last != null && fix.timeMs <= last.timeMs && last.timeMs - fix.timeMs <= MAX_FIX_GAP_MS) return
        lastFix = fix
        if (last == null || fix.timeMs - last.timeMs !in 1..MAX_FIX_GAP_MS) return
        val isStandingStill = fix.speedMps != null && fix.speedMps < STANDSTILL_SPEED_MPS
        if (isStandingStill || !GpsSegment.isPlausible(last, fix, obdSpeedMps)) return
        gpsKm += Geodesy.distanceM(last.position, fix.position) / METRES_PER_KM
    }

    private fun maxOfNullable(current: Double?, candidate: Double?): Double? =
        if (candidate == null) current else maxOf(current ?: candidate, candidate)

    private fun minOfNullable(current: Double?, candidate: Double?): Double? =
        if (candidate == null) current else minOf(current ?: candidate, candidate)

    companion object {
        /** Samples come about once a second; a longer silence is a pause, not driving. */
        const val DEFAULT_MAX_GAP_MS = 10_000L
    }
}
