package nl.stijnvdpol.klinedash.service

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.trip.TripSummarizer
import nl.stijnvdpol.klinedash.core.trip.TripSummary
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.data.TripDao

private const val TAG = "TripRecovery"
private const val CSV_SEPARATOR = ","
private const val LINE_FEED = '\n'.code

/**
 * Rebuilds the summary of a trip from its CSV [lines] (header first). Used for trips that were
 * never finished because the head unit lost power (ACC off) while recording.
 *
 * [endsWithNewline] says whether the file's last byte is a line feed: the writer ends every row
 * with one, so a file that does not was cut in the middle of a row and that row is dropped, because
 * its last number may be truncated. A row whose time cannot be read is skipped too. Distance and
 * maxima are recomputed by feeding the rows through the same [TripSummarizer] as a live trip.
 *
 * Returns null when there is no header or no complete row: nothing worth keeping.
 */
internal fun recoverTripSummary(lines: List<String>, endsWithNewline: Boolean): TripSummary? {
    val complete = if (endsWithNewline) lines else lines.dropLast(1)
    val header = complete.firstOrNull() ?: return null
    val columnOfId = header.split(CSV_SEPARATOR).withIndex().associate { (index, name) -> name.substringBefore(" (") to index }
    val summarizer = TripSummarizer()
    for (line in complete.drop(1)) {
        val cells = line.split(CSV_SEPARATOR)
        val timeMs = parseTimeMs(cells.firstOrNull()) ?: continue
        val readings = SUMMARY_SIGNAL_IDS.mapNotNull { id ->
            val value = columnOfId[id]?.let { cells.getOrNull(it) }?.toDoubleOrNull()
            value?.let { Reading(id, it, timeMs) }
        }.associateBy { it.signalId }
        summarizer.add(timeMs, readings)
    }
    return summarizer.summary()
}

private fun parseTimeMs(cell: String?): Long? = try {
    cell?.let { Instant.parse(it).toEpochMilli() }
} catch (e: DateTimeParseException) {
    null
}

private fun File.endsWithNewline(): Boolean = RandomAccessFile(this, "r").use { file ->
    if (file.length() == 0L) return false
    file.seek(file.length() - 1)
    file.read() == LINE_FEED
}

/**
 * Finishes trips whose row still has no end time. With the app just started none of them can be
 * recording, so they were cut off by a power loss or a crash. The end time, sample count, distance
 * and maxima come from the CSV file (see [recoverTripSummary]); a trip whose file is missing or has
 * no complete row is removed, because there is nothing to export.
 */
internal class TripRecovery(
    private val tripsDir: File,
    private val tripDao: TripDao,
    private val ioDispatcher: CoroutineDispatcher,
) {
    /** Recovers every unfinished trip except [activeTripId] (the one being recorded right now). */
    suspend fun recover(activeTripId: Long?) {
        for (trip in tripDao.getUnfinished()) {
            if (trip.id != activeTripId) recoverOne(trip)
        }
    }

    private suspend fun recoverOne(trip: Trip) {
        val file = resolveTripFile(tripsDir, trip.fileName)
        val summary = try {
            file?.takeIf { it.isFile }?.let { withContext(ioDispatcher) { readSummary(it) } }
        } catch (e: IOException) {
            // Unreadable now does not mean worthless: keep the row and file, retry on the next start.
            Log.e(TAG, "Could not read trip ${trip.id} (${trip.fileName}) for recovery", e)
            return
        }
        if (summary == null) {
            Log.w(TAG, "Trip ${trip.id} (${trip.fileName}) has no usable data, removing it")
            file?.delete()
            tripDao.deleteById(trip.id)
            return
        }
        tripDao.finish(
            id = trip.id,
            endMs = summary.endMs,
            sampleCount = summary.sampleCount,
            distanceKm = summary.distanceKm,
            maxRpm = summary.maxRpm,
            maxSpeedKmh = summary.maxSpeedKmh,
            maxCoolantC = summary.maxCoolantC,
        )
    }

    private fun readSummary(file: File): TripSummary? =
        recoverTripSummary(file.readLines(Charsets.UTF_8), file.endsWithNewline())
}
