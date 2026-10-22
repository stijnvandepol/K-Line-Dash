package nl.stijnvdpol.klinedash.service

import java.util.Locale
import kotlinx.coroutines.flow.Flow
import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.geo.Gate
import nl.stijnvdpol.klinedash.core.geo.Geodesy
import nl.stijnvdpol.klinedash.data.GateSpec
import nl.stijnvdpol.klinedash.data.MAX_TRIPS_PER_TRACK
import nl.stijnvdpol.klinedash.data.Track
import nl.stijnvdpol.klinedash.data.TrackRepository
import nl.stijnvdpol.klinedash.data.TripDao
import nl.stijnvdpol.klinedash.data.newTrack

/** A gate is only set from a fix that is at least this young (monotonic clock): the car is moving. */
const val GATE_MAX_FIX_AGE_MS = 2_000L

/** Worse than this the gate would be placed beside the road; lap timing ignores fixes worse than 20 m anyway. */
const val GATE_MAX_ACCURACY_M = 15.0

/** The heading of a GPS fix is only trustworthy above walking pace; 2 m/s is 7.2 km/h. */
const val GATE_MIN_SPEED_MPS = 2.0

/** Most sector lines on one track. */
const val MAX_SECTOR_LINES = 20

private const val KMH_PER_MPS = 3.6

/** Result of setting a gate at the current position; [Rejected.reason] is a sentence for the user. */
sealed interface GateResult {
    /** [track] is the stored track, already the active one for [LapSessions.startFinishHere]. */
    data class Placed(val track: Track) : GateResult
    data class Rejected(val reason: String) : GateResult
}

/** A gate placed at a fix, or why none can be. */
internal sealed interface GatePlacement {
    data class Ready(val spec: GateSpec) : GatePlacement
    data class Rejected(val reason: String) : GatePlacement
}

/**
 * The gate through [fix], perpendicular to the direction of travel and [widthM] wide. [fix] must be
 * the recent fix (null = none): without a position, an accurate one (at most [GATE_MAX_ACCURACY_M])
 * and a heading (moving at [GATE_MIN_SPEED_MPS] or more) the gate would be in the wrong place or
 * point the wrong way, and every lap after it would be wrong; so these are refused with a reason.
 */
internal fun placeGateAt(fix: GeoFix?, widthM: Double = Gate.DEFAULT_WIDTH_M): GatePlacement {
    if (fix == null) {
        return GatePlacement.Rejected("There is no recent GPS fix. Wait until the GPS status shows a fix, then try again.")
    }
    val accuracy = fix.accuracyM
    if (accuracy == null || accuracy > GATE_MAX_ACCURACY_M) {
        val actual = accuracy?.let { String.format(Locale.US, "%.0f m", it) } ?: "unknown"
        return GatePlacement.Rejected(
            "The GPS position is not accurate enough ($actual, needs ${GATE_MAX_ACCURACY_M.toInt()} m or better). " +
                "Wait a moment and try again.",
        )
    }
    val heading = fix.bearingDeg
    val speed = fix.speedMps
    if (heading == null || speed == null || speed < GATE_MIN_SPEED_MPS) {
        val minimumKmh = String.format(Locale.US, "%.0f", GATE_MIN_SPEED_MPS * KMH_PER_MPS)
        return GatePlacement.Rejected("The driving direction is not known. Set the line while driving at $minimumKmh km/h or faster.")
    }
    return GatePlacement.Ready(GateSpec(fix.lat, fix.lon, Geodesy.normalizeDegrees(heading), widthM))
}

/**
 * Lap sessions: choosing and editing the track (start/finish and sector gates) lap timing runs for.
 * The recording unit stays the trip: a trip records as before and, while a track is active, is also
 * timed live (see [TripRecorder.live]). Laps are not stored; they are derived from the CSV by the
 * gates of the trip's track (see [TripAnalysisRepository]). What is stored is the track, the track
 * of each trip, and a per-trip cache of lap count and best lap for the trip list.
 *
 * A gate changed or a track assigned recomputes the cache of the affected trips from their CSV and
 * removes their generated PC files (they would show the old laps; the next export rewrites them).
 */
class LapSessions(
    private val tracks: TrackRepository,
    private val tripDao: TripDao,
    private val location: LocationSource,
    private val analysis: TripAnalysisRepository,
    private val exporter: TripExporter,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** All tracks, newest first. */
    val allTracks: Flow<List<Track>> get() = tracks.tracks

    /** The track lap timing runs for now, or null. */
    val activeTrack: Flow<Track?> get() = tracks.activeTrack

    /**
     * "Set start/finish here": a new track named [name] with its start/finish line through the current
     * position, across the driving direction, [widthM] wide; it becomes the active track at once (a
     * trip that is recording starts timing laps now). Needs a recent, accurate fix and movement,
     * see [placeGateAt]; otherwise nothing is stored.
     */
    suspend fun startFinishHere(name: String, widthM: Double = Gate.DEFAULT_WIDTH_M): GateResult {
        val placement = placeGateAt(location.freshFix(GATE_MAX_FIX_AGE_MS), widthM)
        if (placement is GatePlacement.Rejected) return GateResult.Rejected(placement.reason)
        val spec = (placement as GatePlacement.Ready).spec
        val track = newTrack(name, spec, emptyList(), clock())
        val saved = track.copy(id = tracks.add(track))
        tracks.setActive(saved.id)
        return GateResult.Placed(saved)
    }

    /**
     * "Set sector line here": appends a sector line at the current position to track [trackId]. Sector
     * lines count in the order they were added, so add them in driving order after the start/finish
     * line. Same requirements as [startFinishHere]; also refused when the track is gone or has the
     * maximum of [MAX_SECTOR_LINES] sectors.
     */
    suspend fun addSectorHere(trackId: Long, widthM: Double = Gate.DEFAULT_WIDTH_M): GateResult {
        val track = tracks.get(trackId) ?: return GateResult.Rejected("This track no longer exists.")
        val sectors = track.sectors
        if (sectors.size >= MAX_SECTOR_LINES) return GateResult.Rejected("A track can have at most $MAX_SECTOR_LINES sector lines.")
        val placement = placeGateAt(location.freshFix(GATE_MAX_FIX_AGE_MS), widthM)
        if (placement is GatePlacement.Rejected) return GateResult.Rejected(placement.reason)
        setSectors(trackId, sectors + (placement as GatePlacement.Ready).spec)
        return GateResult.Placed(checkNotNull(tracks.get(trackId)))
    }

    /** A track from gates the user picked on a recorded trace (no live position needed). Not made active. */
    suspend fun createTrack(name: String, startFinish: GateSpec, sectors: List<GateSpec> = emptyList()): Track {
        val track = newTrack(name, startFinish, sectors, clock())
        return track.copy(id = tracks.add(track))
    }

    /** Replaces the start/finish line of [trackId]; recomputes the trips on this track. */
    suspend fun setStartFinish(trackId: Long, gate: GateSpec) {
        tracks.setStartFinish(trackId, gate)
        recomputeTrack(trackId)
    }

    /** Replaces the sector lines of [trackId] (driving order); recomputes the trips on this track. */
    suspend fun setSectors(trackId: Long, sectors: List<GateSpec>) {
        require(sectors.size <= MAX_SECTOR_LINES) { "At most $MAX_SECTOR_LINES sector lines" }
        tracks.setSectors(trackId, sectors)
        recomputeTrack(trackId)
    }

    suspend fun renameTrack(trackId: Long, name: String) = tracks.rename(trackId, name)

    /** Makes [trackId] the track for lap timing from now on (null: lap timing off). */
    suspend fun setActiveTrack(trackId: Long?) = tracks.setActive(trackId)

    /** Removes the track; its trips keep their data and lose their laps. */
    suspend fun deleteTrack(trackId: Long) {
        val trips = tripDao.getByTrack(trackId, MAX_TRIPS_PER_TRACK)
        tracks.delete(trackId)
        trips.forEach { exporter.deleteExports(it.fileName) }
    }

    /** Uses [trackId] (null: none) for the laps of trip [tripId] and recomputes its cached summary. */
    suspend fun assignTrackToTrip(tripId: Long, trackId: Long?) {
        val trip = tripDao.get(tripId) ?: return
        tripDao.setTrack(tripId, trackId)
        exporter.deleteExports(trip.fileName)
        analysis.refreshSummary(tripId)
    }

    private suspend fun recomputeTrack(trackId: Long) {
        for (trip in tripDao.getByTrack(trackId, MAX_TRIPS_PER_TRACK)) {
            exporter.deleteExports(trip.fileName)
            analysis.refreshSummary(trip.id)
        }
    }
}
