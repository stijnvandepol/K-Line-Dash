package nl.stijnvdpol.klinedash.service

import java.util.Locale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.geo.Gate
import nl.stijnvdpol.klinedash.core.geo.Geodesy
import nl.stijnvdpol.klinedash.core.geo.SectorOrder
import nl.stijnvdpol.klinedash.data.GateSpec
import nl.stijnvdpol.klinedash.data.MAX_SECTOR_LINES
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

/** The trip being recorded, as lap sessions see it; implemented by [TripRecorder]. */
interface LiveLaps {
    /** The live lap timer and laps; [LiveTripState.Idle] when nothing records. */
    val live: StateFlow<LiveTripState>

    /** The fixes of the lap in progress so far, empty while no lap is timed. */
    suspend fun currentLapFixes(): List<GeoFix>
}

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
 * A changed sector list recomputes the cache of the affected trips from their CSV and removes their
 * generated PC files (they would show the old laps; the next export rewrites them).
 */
class LapSessions(
    private val tracks: TrackRepository,
    private val tripDao: TripDao,
    private val location: LocationSource,
    private val analysis: TripAnalysisRepository,
    private val exporter: TripExporter,
    private val liveLaps: LiveLaps,
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
     * "Set sector line here": adds a sector line at the current position to track [trackId], at its
     * place along the lap (lap detection counts sector lines in list order, so a line that lies
     * earlier in the lap than an existing one goes before it): measured on the latest complete lap of
     * the trip being recorded, else on the lap in progress, where the new line is the head of the lap so
     * far and the lines not yet reached lie after it. With no lap to measure on, or one that does not
     * cross every line, it is appended; [keepSectorsOrdered] puts the lines in order once a lap does.
     * Same requirements as [startFinishHere]; also refused when the track is gone or has the maximum
     * of [MAX_SECTOR_LINES] sectors.
     */
    suspend fun addSectorHere(trackId: Long, widthM: Double = Gate.DEFAULT_WIDTH_M): GateResult {
        val track = tracks.get(trackId) ?: return GateResult.Rejected("This track no longer exists.")
        val sectors = track.sectors
        if (sectors.size >= MAX_SECTOR_LINES) return GateResult.Rejected("A track can have at most $MAX_SECTOR_LINES sector lines.")
        val placement = placeGateAt(location.freshFix(GATE_MAX_FIX_AGE_MS), widthM)
        if (placement is GatePlacement.Rejected) return GateResult.Rejected(placement.reason)
        setSectors(trackId, sectorsWith(trackId, sectors, (placement as GatePlacement.Ready).spec))
        return GateResult.Placed(checkNotNull(tracks.get(trackId)))
    }

    /** [existing] with [added] (at the head of the lap so far) at its place in driving order, see [addSectorHere]. */
    private suspend fun sectorsWith(trackId: Long, existing: List<GateSpec>, added: GateSpec): List<GateSpec> {
        val state = liveLaps.live.value
        if (state.trackId != trackId) return existing + added
        val completeLap = state.lapTiming?.referenceLap.orEmpty()
        if (completeLap.isNotEmpty()) return SectorOrder.orderAlongLap(existing + added, completeLap) { it.toGate() }
        val lapSoFar = liveLaps.currentLapFixes()
        if (lapSoFar.isEmpty()) return existing + added
        val headM = SectorOrder.lapLengthM(lapSoFar)
        return SectorOrder.sortedByPosition(existing + added) { spec ->
            if (spec === added) headM else SectorOrder.positionAlongLapM(spec.toGate(), lapSoFar)
        }
    }

    /**
     * Puts the sector lines of [trackId] in driving order along [lap] (a complete lap of that track),
     * storing and recomputing only when the order changes; a list the lap cannot fully measure is left alone.
     */
    suspend fun orderSectors(trackId: Long, lap: List<GeoFix>) {
        val sectors = tracks.get(trackId)?.sectors ?: return
        val ordered = SectorOrder.orderAlongLap(sectors, lap) { it.toGate() }
        if (ordered != sectors) setSectors(trackId, ordered)
    }

    /**
     * Keeps the sector lines of the active track in driving order, for as long as it runs (the
     * service launches it): against the newest recorded trip with a complete lap when a track becomes
     * active, which repairs tracks whose lines were stored in the order they were added, and against
     * every lap the trip being recorded completes, which orders lines that were appended before any lap
     * was known.
     */
    suspend fun keepSectorsOrdered() = coroutineScope {
        launch {
            tracks.activeTrack.map { it?.id }.distinctUntilChanged().filterNotNull()
                .collect { trackId -> orderSectors(trackId, analysis.referenceLap(trackId)) }
        }
        launch {
            liveLaps.live.map { state -> state.trackId?.let { id -> state.lapTiming?.referenceLap?.takeIf { it.isNotEmpty() }?.let { id to it } } }
                .distinctUntilChanged().filterNotNull()
                .collect { (trackId, lap) -> orderSectors(trackId, lap) }
        }
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

    private suspend fun recomputeTrack(trackId: Long) {
        for (trip in tripDao.getByTrack(trackId, MAX_TRIPS_PER_TRACK)) {
            exporter.deleteExports(trip.fileName)
            analysis.refreshSummary(trip.id)
        }
    }
}
