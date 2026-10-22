package nl.stijnvdpol.klinedash.service

import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.geo.Lap
import nl.stijnvdpol.klinedash.core.geo.LatLon
import nl.stijnvdpol.klinedash.core.trip.LapStats
import nl.stijnvdpol.klinedash.core.trip.TripAnalyzer
import nl.stijnvdpol.klinedash.core.trip.TripCsv
import nl.stijnvdpol.klinedash.core.trip.TripStats
import nl.stijnvdpol.klinedash.core.trip.readTripCsv
import nl.stijnvdpol.klinedash.data.Track
import nl.stijnvdpol.klinedash.data.TrackDao
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.data.TripDao
import nl.stijnvdpol.klinedash.data.updateSummary

private const val TAG = "TripAnalysis"

/** How many of the newest trips on a track are searched for a complete lap. */
private const val REFERENCE_LAP_TRIPS = 5

/**
 * Everything the trip detail screen needs, computed from the trip's CSV.
 *
 * @property stats the statistics; null for a file without a readable row.
 * @property laps the completed laps with their statistics for [track]; empty without a track.
 * @property track the track the laps were computed for, null when the trip has none (or it was deleted).
 * @property hasGps true when at least one row has a position.
 * @property route the driven line, reduced to at most [MAX_ROUTE_POINTS] points for drawing; empty without GPS.
 */
data class TripDetail(
    val tripId: Long,
    val stats: TripStats?,
    val laps: List<LapStats>,
    val track: Track?,
    val hasGps: Boolean,
    val route: List<LatLon>,
)

/** Result of [TripAnalysisRepository.analyze]; [Failed.reason] is a sentence for the user. */
sealed interface AnalysisResult {
    data class Done(val detail: TripDetail) : AnalysisResult
    data object TripNotFound : AnalysisResult
    data class Failed(val reason: String) : AnalysisResult
}

/** Runs [block] on the parsed CSV [file]; the file stays open until it returns, because the samples are read lazily. */
internal inline fun <T> withTripCsv(file: File, block: (TripCsv) -> T): T =
    file.bufferedReader(Charsets.UTF_8).use { block(readTripCsv(it)) }

/**
 * Statistics, laps and route of recorded trips, derived from the CSV with the same code that runs
 * live while recording (so a track change recomputes old trips exactly as a live run would have
 * seen them). All reading happens off the main thread.
 */
class TripAnalysisRepository internal constructor(
    private val tripsDir: File,
    private val tripDao: TripDao,
    private val trackDao: TrackDao,
    private val ioDispatcher: CoroutineDispatcher,
) {
    constructor(tripsDir: File, tripDao: TripDao, trackDao: TrackDao) : this(tripsDir, tripDao, trackDao, Dispatchers.IO)

    /** Reads the CSV of trip [tripId] once and returns its statistics, laps for the trip's track, and the route. */
    suspend fun analyze(tripId: Long): AnalysisResult {
        val trip = tripDao.get(tripId) ?: return AnalysisResult.TripNotFound
        val file = resolveTripFile(tripsDir, trip.fileName)?.takeIf { it.isFile }
            ?: return AnalysisResult.Failed("The trip file is missing.")
        val track = trip.trackId?.let { trackDao.get(it) }
        return try {
            AnalysisResult.Done(withContext(ioDispatcher) { analyzeFile(trip, file, track) })
        } catch (e: IOException) {
            Log.e(TAG, "Could not read ${trip.fileName}", e)
            AnalysisResult.Failed("Could not read the trip file. Try again.")
        }
    }

    /**
     * The route and the speed and rpm of one [lap] of trip [tripId], for the lap graph of the detail
     * screen. Null when the trip file is missing or cannot be read (logged); the lap itself comes from [analyze].
     */
    suspend fun lapTrace(tripId: Long, lap: Lap): LapTrace? {
        val trip = tripDao.get(tripId) ?: return null
        val file = resolveTripFile(tripsDir, trip.fileName)?.takeIf { it.isFile } ?: return null
        return try {
            withContext(ioDispatcher) { withTripCsv(file) { csv -> lapTraceOf(csv.samples, lap) } }
        } catch (e: IOException) {
            Log.e(TAG, "Could not read lap ${lap.number} of ${trip.fileName}", e)
            null
        }
    }

    /**
     * The fixes of the latest valid complete lap on [trackId], from the newest of its finished trips
     * that has one (looking at the newest [REFERENCE_LAP_TRIPS]); empty when there is none or the files
     * cannot be read (logged). It tells where the sector lines of the track lie along a lap.
     */
    suspend fun referenceLap(trackId: Long): List<GeoFix> {
        val track = trackDao.get(trackId) ?: return emptyList()
        for (trip in tripDao.getByTrack(trackId, REFERENCE_LAP_TRIPS).filter { it.endMs != null }) {
            val file = resolveTripFile(tripsDir, trip.fileName)?.takeIf { it.isFile } ?: continue
            val lap = try {
                withContext(ioDispatcher) { referenceLapOf(file, track) }
            } catch (e: IOException) {
                Log.e(TAG, "Could not read ${trip.fileName} for a reference lap", e)
                continue
            }
            if (lap.isNotEmpty()) return lap
        }
        return emptyList()
    }

    /** Recomputes the cached statistics of the trip row (what the trip list shows) from the CSV. */
    suspend fun refreshSummary(tripId: Long): AnalysisResult {
        val result = analyze(tripId)
        if (result is AnalysisResult.Done) {
            val detail = result.detail
            val laps = detail.laps.takeIf { detail.track != null }
            tripDao.updateSummary(tripId, tripSummaryCache(detail.stats, laps, detail.hasGps))
        }
        return result
    }

    private fun referenceLapOf(file: File, track: Track): List<GeoFix> = withTripCsv(file) { csv ->
        val analyzer = TripAnalyzer(track.lapGates())
        csv.samples.forEach(analyzer::add)
        analyzer.timing()?.referenceLap.orEmpty()
    }

    private fun analyzeFile(trip: Trip, file: File, track: Track?): TripDetail = withTripCsv(file) { csv ->
        val analyzer = TripAnalyzer(track?.lapGates())
        val fixes = ArrayList<GeoFix>()
        for (sample in csv.samples) {
            analyzer.add(sample)
            sample.gps?.let(fixes::add)
        }
        TripDetail(trip.id, analyzer.stats(), analyzer.lapStats(), track, fixes.isNotEmpty(), routeOf(fixes))
    }
}
