package nl.stijnvdpol.klinedash.service

import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private fun analyzeFile(trip: Trip, file: File, track: Track?): TripDetail = withTripCsv(file) { csv ->
        val analyzer = TripAnalyzer(track?.lapGates())
        val route = ArrayList<LatLon>()
        var lastFixMs = Long.MIN_VALUE
        for (sample in csv.samples) {
            analyzer.add(sample)
            val fix = sample.gps ?: continue
            if (fix.timeMs == lastFixMs) continue
            lastFixMs = fix.timeMs
            route += fix.position
        }
        TripDetail(trip.id, analyzer.stats(), analyzer.lapStats(), track, route.isNotEmpty(), decimateRoute(route))
    }
}
