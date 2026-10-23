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
 * How far back a lap session looks for the pass at which it was set. A start/finish line is set
 * from a fix of at most this age (GATE_MAX_FIX_AGE_MS in the app), and the recorder needs a moment
 * to hand the new line over, so the crossing of the line lies in the last seconds before the lap
 * session starts.
 */
const val LAP_LEAD_IN_MS = 2_000L

/** Bounds the lead-in when fixes come faster than expected or their clock jumps. */
private const val MAX_LEAD_IN_SAMPLES = 20

/**
 * The most recent samples with a new GPS fix, up to [LAP_LEAD_IN_MS] back plus the fix before them.
 * A lap session that starts now is fed these first, so the line it was set at is crossed by a
 * segment it has seen, whichever side of the line floating point puts the fix it was set from.
 */
private class LapLeadIn {
    private val samples = ArrayDeque<TripSample>()

    fun record(sample: TripSample) {
        val fixMs = sample.gps?.timeMs ?: return
        if (samples.lastOrNull()?.fixMs() == fixMs) return
        samples.addLast(sample)
        while (samples.size > MAX_LEAD_IN_SAMPLES || (samples.size > 2 && fixMs - samples[1].fixMs() > LAP_LEAD_IN_MS)) {
            samples.removeFirst()
        }
    }

    private fun TripSample.fixMs(): Long = checkNotNull(gps).timeMs

    fun samples(): List<TripSample> = samples.toList()

    fun clear() = samples.clear()
}

/**
 * Live and after-the-fact analysis of a trip in one place: feed [TripSample]s in time order with
 * [add]; [stats], [lapStats] and [timing] can be read after every sample. Without [gates] only the
 * trip statistics are computed. A recorded trip is analysed by feeding its samples through the same
 * code ([analyzeTrip]), so a changed gate recomputes old trips exactly as a live run would have.
 *
 * Lap timing covers the lap session only: the part of the trip from the moment its lines were set.
 * That moment is passed live with [startLapSession] and, for a recorded trip, as [lapTimingAfterMs]
 * (the time of the last sample before it, see [lapTimingAfterMs]); null there means the whole trip.
 * Both start the lap detector with the lead-in (see [LAP_LEAD_IN_MS]), so a pass of the line at
 * that moment is the start of lap 1 live and in the recording alike, and passes before it are in
 * neither.
 */
class TripAnalyzer(
    gates: LapGates? = null,
    private val lapConfig: LapDetectorConfig = LapDetectorConfig(),
    lapTimingAfterMs: Long? = null,
) {
    private val tripStats = TripStatsAccumulator()
    private val leadIn = LapLeadIn()
    private var detector: LapDetector? = null
    private val finishedLaps = ArrayList<LapStats>()
    private var currentLap = TripStatsAccumulator()
    private var latestSampleMs: Long? = null
    private var pendingGates: LapGates? = null
    private val pendingAfterMs: Long? = lapTimingAfterMs

    /**
     * The time of the last sample before the lap session started; the session is fed the samples
     * after it (and the lead-in before). Null when it covers the trip from the first sample.
     */
    var lapTimingAfterMs: Long? = null
        private set

    init {
        when {
            gates == null -> Unit
            pendingAfterMs == null -> startLapSession(gates)
            else -> {
                pendingGates = gates
                this.lapTimingAfterMs = pendingAfterMs
            }
        }
    }

    /**
     * Starts lap timing for [gates] from now on, replacing the laps of an earlier session. The
     * detector first sees the lead-in, so a pass of the line just before this call counts.
     */
    fun startLapSession(gates: LapGates) {
        pendingGates = null
        lapTimingAfterMs = latestSampleMs
        detector = LapDetector(gates, lapConfig)
        finishedLaps.clear()
        currentLap = TripStatsAccumulator()
        leadIn.samples().forEach(::addToLaps)
    }

    /** Ends lap timing and forgets its laps. */
    fun endLapSession() {
        pendingGates = null
        detector = null
        finishedLaps.clear()
        currentLap = TripStatsAccumulator()
    }

    fun add(sample: TripSample) {
        tripStats.add(sample)
        val pending = pendingGates
        if (pending != null && sample.timeMs > checkNotNull(pendingAfterMs)) startLapSession(pending)
        addToLaps(sample)
        leadIn.record(sample)
        latestSampleMs = sample.timeMs
    }

    private fun addToLaps(sample: TripSample) {
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
        leadIn.clear()
    }

    /** Replaces the sector gates without restarting lap timing, see [LapDetector.updateSectors]. */
    fun updateSectors(sectors: List<Gate>) {
        detector?.updateSectors(sectors)
    }

    /** Null until the first sample has been added. */
    fun stats(): TripStats? = tripStats.stats()

    /** The completed laps in order. */
    fun lapStats(): List<LapStats> = finishedLaps.toList()

    /** The live lap-timer state; null while no lap session runs. */
    fun timing(): LapTimingState? = detector?.state

    /** The fixes of the lap in progress so far, see [LapDetector.currentLapFixes]; empty without a lap session. */
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

/**
 * Analyses a recorded trip: the statistics, plus laps and their statistics when [gates] are given.
 * [lapTimingAfterMs] is where the lap session started, see [TripAnalyzer]; null times the whole trip.
 */
fun analyzeTrip(
    samples: Sequence<TripSample>,
    gates: LapGates? = null,
    lapConfig: LapDetectorConfig = LapDetectorConfig(),
    lapTimingAfterMs: Long? = null,
): TripAnalysis {
    val analyzer = TripAnalyzer(gates, lapConfig, lapTimingAfterMs)
    samples.forEach(analyzer::add)
    return TripAnalysis(analyzer.stats(), analyzer.lapStats())
}
