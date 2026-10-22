package nl.stijnvdpol.klinedash.service

import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.geo.LapDetector
import nl.stijnvdpol.klinedash.core.geo.LapGates
import nl.stijnvdpol.klinedash.core.geo.LapTimingState
import nl.stijnvdpol.klinedash.core.trip.LapStats
import nl.stijnvdpol.klinedash.core.trip.TripAnalyzer
import nl.stijnvdpol.klinedash.core.trip.TripSample
import nl.stijnvdpol.klinedash.core.trip.TripStats
import nl.stijnvdpol.klinedash.core.trip.TripSummary
import nl.stijnvdpol.klinedash.data.TripSummaryCache

/**
 * What the screens show about the trip being recorded, updated with every sample.
 *
 * @property stats the trip so far; null while idle or before the first sample.
 * @property lapTiming the lap timer (current lap, last/best lap, delta, predicted lap); null without
 *   a track.
 * @property lapStats the completed laps so far, in order.
 * @property trackId the track the laps are timed for, null without one.
 */
data class LiveTripState(
    val stats: TripStats?,
    val lapTiming: LapTimingState?,
    val lapStats: List<LapStats>,
    val trackId: Long?,
) {
    companion object {
        /** Nothing is recording. */
        val Idle = LiveTripState(null, null, emptyList(), null)
    }
}

/**
 * The statistics of the trip in progress: one analyzer for the whole trip and, once a track is set,
 * a second one with the gates for the laps. A track can be set or changed at any moment (the user
 * presses "set start/finish here" while driving); lap timing then starts from that moment, because
 * nothing before it could cross a gate that did not exist yet. Only sector lines added to or
 * changed on the same track keep the lap timer and the laps so far (a sector line does not move
 * the start of a lap). Not thread-safe; [TripSession] calls it under the recorder's lock.
 */
internal class LiveTripAnalysis {
    private val overall = TripAnalyzer()
    private var lapped: TripAnalyzer? = null
    private var hasGps = false

    var trackId: Long? = null
        private set

    var gates: LapGates? = null
        private set

    /**
     * Starts lap timing for [gates] from now on, or ends it with null. Replaces the laps of a previous
     * track or start/finish line; new sector lines on the same track and line are taken over in place.
     */
    fun setTrack(trackId: Long?, gates: LapGates?) {
        val current = lapped
        if (current != null && gates != null && trackId == this.trackId && gates.startFinish == this.gates?.startFinish) {
            this.gates = gates
            current.updateSectors(gates.sectors)
            return
        }
        this.trackId = trackId.takeIf { gates != null }
        this.gates = gates
        lapped = gates?.let { TripAnalyzer(it) }
    }

    /** The fixes of the lap in progress so far, see [LapDetector.currentLapFixes]; empty without a track. */
    fun currentLapFixes(): List<GeoFix> = lapped?.currentLapFixes().orEmpty()

    /** The location source changed: no distance between the old source's last fix and the new one's first. */
    fun startNewGpsSegment() {
        overall.startNewGpsSegment()
        lapped?.startNewGpsSegment()
    }

    fun add(sample: TripSample) {
        if (sample.gps != null) hasGps = true
        overall.add(sample)
        lapped?.add(sample)
    }

    fun state(): LiveTripState = LiveTripState(overall.stats(), lapped?.timing(), lapped?.lapStats().orEmpty(), trackId)

    /** The totals that go into the trip row when it ends; null while no sample was added. */
    fun summary(): TripSummary? = overall.stats()?.toTripSummary()

    /** The cached statistics of the trip row; laps are null without a track. */
    fun cache(): TripSummaryCache = tripSummaryCache(overall.stats(), lapped?.lapStats(), hasGps)
}

/** The cache columns of a trip row from its statistics; [laps] is null when the trip has no track. */
internal fun tripSummaryCache(stats: TripStats?, laps: List<LapStats>?, hasGps: Boolean): TripSummaryCache =
    TripSummaryCache(
        hasGps = hasGps,
        movingTimeMs = stats?.movingTimeMs,
        estimatedFuelLiters = stats?.estimatedFuelLiters,
        lapCount = laps?.size,
        bestLapMs = laps?.filter { it.lap.isValid }?.minOfOrNull { it.lap.lapTimeMs },
    )

/** The totals stored in the trip row. */
internal fun TripStats.toTripSummary() = TripSummary(
    startMs = startMs,
    endMs = endMs,
    sampleCount = sampleCount,
    distanceKm = distanceKm,
    maxRpm = maxRpm,
    maxSpeedKmh = maxSpeedKmh,
    maxCoolantC = maxCoolantC,
)
