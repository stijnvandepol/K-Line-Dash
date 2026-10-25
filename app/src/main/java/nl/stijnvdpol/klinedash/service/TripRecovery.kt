package nl.stijnvdpol.klinedash.service

import android.database.SQLException
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.Reader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import nl.stijnvdpol.klinedash.core.trip.TripStats
import nl.stijnvdpol.klinedash.core.trip.TripStatsAccumulator
import nl.stijnvdpol.klinedash.core.trip.TripSummary
import nl.stijnvdpol.klinedash.core.trip.readTripCsv
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.data.TripDao
import nl.stijnvdpol.klinedash.data.updateSummary

private const val TAG = "TripRecovery"

/** A trip rebuilt from its CSV: the statistics and whether any row has a position. */
internal class RecoveredTrip(val stats: TripStats, val hasGps: Boolean)

/**
 * Rebuilds the statistics of a trip from its CSV, read from [reader] row by row (an unfinished trip
 * can be large, and this runs under the recorder lock). Used for trips that were never finished
 * because the head unit lost power (ACC off) while recording.
 *
 * A file whose last byte is not a line feed was cut in the middle of a row, because the writer ends
 * every row with one; that row is dropped, its last number may be truncated ([readTripCsv] does
 * that). The rows go through the same [TripStatsAccumulator] as a live trip, so distance (GPS where
 * fixes exist) and maxima agree.
 *
 * Returns null when there is no header or no complete row: nothing worth keeping.
 */
internal fun recoverTrip(reader: Reader): RecoveredTrip? {
    val accumulator = TripStatsAccumulator()
    var hasGps = false
    for (sample in readTripCsv(reader).samples) {
        accumulator.add(sample)
        if (sample.gps != null) hasGps = true
    }
    return accumulator.stats()?.let { RecoveredTrip(it, hasGps) }
}

/** The trip row totals of [recoverTrip]. */
internal fun recoverTripSummary(reader: Reader): TripSummary? = recoverTrip(reader)?.stats?.toTripSummary()

/** The lap session is over: hands [trackId] to [release]; a failing write is logged, the trip is over either way. */
internal suspend fun releaseActiveTrack(trackId: Long, release: suspend (trackId: Long) -> Unit) {
    try {
        release(trackId)
    } catch (e: SQLException) {
        Log.e(TAG, "Could not release the active track $trackId", e)
    }
}

/** The row is finished already; if [refresh] fails the list only lacks the lap count until the track changes. */
internal suspend fun refreshLapSummary(tripId: Long, refresh: suspend (tripId: Long) -> Unit) {
    try {
        refresh(tripId)
    } catch (e: SQLException) {
        Log.e(TAG, "Could not refresh the lap summary of trip $tripId", e)
    }
}

/**
 * Finishes trips whose row still has no end time. With the app just started none of them can be
 * recording, so they were cut off by a power loss or a crash. The end time, sample count, distance
 * and maxima come from the CSV file (see [recoverTripSummary]); a trip whose file is missing or has
 * no complete row is removed, because there is nothing to export. A trip with a track (a lap session
 * cut off by the ignition, the usual end on a head unit) gets its lap count and best lap from
 * [refreshSummary], like a trip that ended normally. Its lap session ended with it, so the track is
 * handed to [releaseTrack] too, as when a trip is finished normally (see [TripRecorder]).
 */
internal class TripRecovery(
    private val tripsDir: File,
    private val tripDao: TripDao,
    private val ioDispatcher: CoroutineDispatcher,
    private val refreshSummary: suspend (tripId: Long) -> Unit = {},
    private val releaseTrack: suspend (trackId: Long) -> Unit = {},
) {
    /** Recovers every unfinished trip except [activeTripId] (the one being recorded right now). */
    suspend fun recover(activeTripId: Long?) {
        for (trip in tripDao.getUnfinished()) {
            if (trip.id != activeTripId) recoverOne(trip)
        }
    }

    private suspend fun recoverOne(trip: Trip) {
        recoverTripRow(trip)
        trip.trackId?.let { releaseActiveTrack(it, releaseTrack) }
    }

    private suspend fun recoverTripRow(trip: Trip) {
        val file = withContext(ioDispatcher) { resolveExistingTripFile(tripsDir, trip.fileName) }
        val recovered = try {
            file?.let { withContext(ioDispatcher) { readTrip(it) } }
        } catch (e: IOException) {
            // Unreadable now does not mean worthless: keep the row and file, retry on the next start.
            Log.e(TAG, "Could not read trip ${trip.id} (${trip.fileName}) for recovery", e)
            return
        }
        if (recovered == null) {
            Log.w(TAG, "Trip ${trip.id} (${trip.fileName}) has no usable data, removing it")
            file?.delete()
            tripDao.deleteById(trip.id)
            return
        }
        val stats = recovered.stats
        tripDao.finish(
            id = trip.id,
            endMs = stats.endMs,
            sampleCount = stats.sampleCount,
            distanceKm = stats.distanceKm,
            maxRpm = stats.maxRpm,
            maxSpeedKmh = stats.maxSpeedKmh,
            maxCoolantC = stats.maxCoolantC,
        )
        tripDao.updateSummary(trip.id, tripSummaryCache(stats, laps = null, hasGps = recovered.hasGps))
        if (trip.trackId != null) refreshLapSummary(trip.id, refreshSummary)
    }

    private fun readTrip(file: File): RecoveredTrip? = file.bufferedReader(Charsets.UTF_8).use(::recoverTrip)
}
