package nl.stijnvdpol.klinedash.core.geo

/**
 * Tuning of the lap detection.
 *
 * @property minLapFloorMs a lap shorter than this is never accepted (GPS jitter at the gate).
 * @property minLapBestFraction a lap shorter than this fraction of the best lap is not accepted.
 * @property rearmDistanceM after a start/finish crossing the car must get this far from the gate
 *   before the next crossing counts.
 * @property maxFixGapMs a longer silence between two fixes is a GPS dropout: nothing is interpolated
 *   across it and the lap in progress becomes invalid.
 * @property maxAccuracyM fixes that are less accurate than this are ignored.
 */
data class LapDetectorConfig(
    val minLapFloorMs: Long = 10_000L,
    val minLapBestFraction: Double = 0.5,
    val rearmDistanceM: Double = 50.0,
    val maxFixGapMs: Long = 3_000L,
    val maxAccuracyM: Double = 20.0,
)

/**
 * Incremental lap detector: feed fixes in time order with [add], read the live state from [state].
 * Pure and deterministic; the same fixes always give the same laps, live or recomputed afterwards
 * with [detectLaps].
 *
 * The first start/finish crossing starts lap 1 (the drive before it is not a lap). A crossing is
 * ignored when it goes the wrong way, comes before the minimum lap time, or happens before the car
 * has left the gate area since the last crossing. Sector gates only count in order within a lap.
 * Fixes that are inaccurate or not newer than the previous one are ignored. A standing start that
 * jitters over the line can begin lap 1 slightly early.
 */
class LapDetector(
    private val gates: LapGates,
    private val config: LapDetectorConfig = LapDetectorConfig(),
) {
    private class ActiveLap(val startMs: Long) {
        val splitsMs = ArrayList<Long>()
        val profile = LapProfileBuilder()
        var isValid = true
    }

    private var previous: GeoFix? = null
    private var active: ActiveLap? = null
    private var isGateArmed = true
    private var lastLap: Lap? = null
    private var bestLap: Lap? = null
    private var bestProfile: LapProfile? = null
    private var completedLaps = 0

    /** Processes [fix]; returns the lap that ended with it, if any. */
    fun add(fix: GeoFix): Lap? {
        if (fix.accuracyM != null && fix.accuracyM > config.maxAccuracyM) return null
        val from = previous
        if (from != null && fix.timeMs <= from.timeMs) return null
        previous = fix
        if (from == null) return null
        if (Geodesy.distanceM(gates.startFinish.midpoint, fix.position) > config.rearmDistanceM) isGateArmed = true
        if (fix.timeMs - from.timeMs > config.maxFixGapMs) {
            active?.isValid = false
            return null
        }
        return advance(from, fix)
    }

    val state: LapTimingState
        get() {
            val lap = active
            val latest = previous
            val elapsed = if (lap != null && latest != null) latest.timeMs - lap.startMs else null
            val delta = lap?.let { deltaToBest(it) }
            return LapTimingState(
                isTiming = lap != null,
                currentLapElapsedMs = elapsed,
                currentSplitsMs = lap?.splitsMs?.map { it - lap.startMs }.orEmpty(),
                lastLap = lastLap,
                bestLap = bestLap,
                lapCount = completedLaps,
                deltaToBestMs = delta,
                predictedLapMs = delta?.let { bestLap?.lapTimeMs?.plus(it) },
            )
        }

    private fun deltaToBest(lap: ActiveLap): Long? {
        val reference = bestProfile?.elapsedAtDistance(lap.profile.distanceM) ?: return null
        return lap.profile.elapsedMs - reference
    }

    private fun advance(from: GeoFix, to: GeoFix): Lap? {
        val segmentM = Geodesy.distanceM(from.position, to.position)
        val lap = active
        if (lap != null) recordSectorSplit(lap, from, to)
        val crossing = if (isGateArmed) gates.startFinish.crossing(from, to) else null
        if (crossing == null || !isLongEnough(lap, crossing)) {
            lap?.let { it.profile.addPoint(it.profile.distanceM + segmentM, to.timeMs - it.startMs) }
            return null
        }
        isGateArmed = false
        val finished = lap?.let { finish(it, crossing, segmentM) }
        active = startLap(crossing, to, segmentM)
        return finished
    }

    private fun startLap(crossing: GateCrossing, to: GeoFix, segmentM: Double): ActiveLap =
        ActiveLap(crossing.timeMs).also {
            it.profile.addPoint(0.0, 0L)
            it.profile.addPoint((1 - crossing.fraction) * segmentM, to.timeMs - crossing.timeMs)
        }

    private fun isLongEnough(lap: ActiveLap?, crossing: GateCrossing): Boolean {
        if (lap == null) return true
        val bestMs = bestLap?.lapTimeMs ?: 0L
        val minimumMs = maxOf(config.minLapFloorMs, (bestMs * config.minLapBestFraction).toLong())
        return crossing.timeMs - lap.startMs >= minimumMs
    }

    private fun recordSectorSplit(lap: ActiveLap, from: GeoFix, to: GeoFix) {
        val gate = gates.sectors.getOrNull(lap.splitsMs.size) ?: return
        gate.crossing(from, to)?.let { lap.splitsMs += it.timeMs }
    }

    private fun finish(lap: ActiveLap, crossing: GateCrossing, segmentM: Double): Lap {
        val elapsedMs = crossing.timeMs - lap.startMs
        lap.profile.addPoint(lap.profile.distanceM + crossing.fraction * segmentM, elapsedMs)
        val result = Lap(
            number = ++completedLaps,
            startMs = lap.startMs,
            endMs = crossing.timeMs,
            sectorTimesMs = sectorTimes(lap, crossing.timeMs),
            distanceM = lap.profile.distanceM,
            isValid = lap.isValid,
        )
        lastLap = result
        if (result.isValid && result.lapTimeMs < (bestLap?.lapTimeMs ?: Long.MAX_VALUE)) {
            bestLap = result
            bestProfile = lap.profile.build()
        }
        return result
    }

    private fun sectorTimes(lap: ActiveLap, endMs: Long): List<Long> {
        if (gates.sectors.isEmpty() || lap.splitsMs.size != gates.sectors.size) return emptyList()
        val boundaries = listOf(lap.startMs) + lap.splitsMs + endMs
        return boundaries.zipWithNext { start, end -> end - start }
    }
}

/** Recomputes the laps of a recorded trip from its [fixes] (time ordered) and the current [gates]. */
fun detectLaps(
    fixes: Iterable<GeoFix>,
    gates: LapGates,
    config: LapDetectorConfig = LapDetectorConfig(),
): List<Lap> {
    val detector = LapDetector(gates, config)
    return fixes.mapNotNull(detector::add)
}
