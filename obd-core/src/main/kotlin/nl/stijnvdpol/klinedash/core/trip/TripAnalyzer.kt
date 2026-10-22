package nl.stijnvdpol.klinedash.core.trip

import nl.stijnvdpol.klinedash.core.geo.Gate
import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.geo.Lap
import nl.stijnvdpol.klinedash.core.geo.LapDetector
import nl.stijnvdpol.klinedash.core.geo.LapDetectorConfig
import nl.stijnvdpol.klinedash.core.geo.LapGates
import nl.stijnvdpol.klinedash.core.geo.LapTimingState

/**
 * Statistics of one lap. Speed, rpm and throttle figures cover the samples between the lap's start
 * and end crossing; the sample taken just around a crossing can land on either side, so they are
 * accurate to about one sample interval. Time, sector times and distance come from the [lap] itself.
 */
data class LapStats(
    val lap: Lap,
    val maxSpeedKmh: Double?,
    val minSpeedKmh: Double?,
    val maxRpm: Double?,
    val fullThrottleTimeMs: Long,
) {
    val distanceKm: Double get() = lap.distanceM / METRES_PER_KM

    /** Distance over lap time; null for a zero-length lap. */
    val averageSpeedKmh: Double? get() = averageKmh(distanceKm, lap.lapTimeMs)

    private companion object {
        const val METRES_PER_KM = 1_000.0
    }
}

/** Everything computed from a whole trip. */
data class TripAnalysis(val stats: TripStats?, val laps: List<LapStats>)

/**
 * Live and after-the-fact analysis of a trip in one place: feed [TripSample]s in time order with
 * [add]; [stats], [lapStats] and [timing] can be read after every sample. Without [gates] only the
 * trip statistics are computed. A recorded trip is analysed by feeding its samples through the same
 * code ([analyzeTrip]), so a changed gate recomputes old trips exactly as a live run would have.
 */
class TripAnalyzer(
    gates: LapGates? = null,
    lapConfig: LapDetectorConfig = LapDetectorConfig(),
) {
    private val tripStats = TripStatsAccumulator()
    private val detector = gates?.let { LapDetector(it, lapConfig) }
    private val finishedLaps = ArrayList<LapStats>()
    private var currentLap = TripStatsAccumulator()

    fun add(sample: TripSample) {
        tripStats.add(sample)
        val detector = detector ?: return
        val lap = sample.gps?.let(detector::add)
        if (lap != null) {
            finishedLaps += lapStatsOf(lap, currentLap)
            currentLap = TripStatsAccumulator()
        }
        if (detector.state.isTiming) {
            currentLap.add(sample)
        } else {
            // A lap the detector dropped (another location source or clock) takes its samples along.
            currentLap = TripStatsAccumulator()
        }
    }

    /** The location source changed: the next fix starts a new GPS segment for the trip and the laps. */
    fun startNewGpsSegment() {
        tripStats.startNewGpsSegment()
        currentLap.startNewGpsSegment()
        detector?.startNewSegment()
    }

    /** Replaces the sector gates without restarting lap timing, see [LapDetector.updateSectors]. */
    fun updateSectors(sectors: List<Gate>) {
        detector?.updateSectors(sectors)
    }

    /** Null until the first sample has been added. */
    fun stats(): TripStats? = tripStats.stats()

    /** The completed laps in order. */
    fun lapStats(): List<LapStats> = finishedLaps.toList()

    /** The live lap-timer state; null when no gates were given. */
    fun timing(): LapTimingState? = detector?.state

    /** The fixes of the lap in progress so far, see [LapDetector.currentLapFixes]; empty without gates. */
    fun currentLapFixes(): List<GeoFix> = detector?.currentLapFixes().orEmpty()

    private fun lapStatsOf(lap: Lap, accumulated: TripStatsAccumulator): LapStats {
        val stats = accumulated.stats()
        return LapStats(
            lap = lap,
            maxSpeedKmh = stats?.maxSpeedKmh,
            minSpeedKmh = stats?.minSpeedKmh,
            maxRpm = stats?.maxRpm,
            fullThrottleTimeMs = stats?.fullThrottleTimeMs ?: 0L,
        )
    }
}

/** Analyses a recorded trip: the statistics, plus laps and their statistics when [gates] are given. */
fun analyzeTrip(
    samples: Sequence<TripSample>,
    gates: LapGates? = null,
    lapConfig: LapDetectorConfig = LapDetectorConfig(),
): TripAnalysis {
    val analyzer = TripAnalyzer(gates, lapConfig)
    samples.forEach(analyzer::add)
    return TripAnalysis(analyzer.stats(), analyzer.lapStats())
}
