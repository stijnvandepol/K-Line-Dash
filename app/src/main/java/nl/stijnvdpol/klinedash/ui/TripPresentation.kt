package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.service.CopyProgress
import nl.stijnvdpol.klinedash.service.StartResult
import nl.stijnvdpol.klinedash.service.formatNumber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * What the TRIPS page says, as plain functions: the clock and list values in fixed formats, the
 * sentence after a copy to USB, the question before a delete. The composables only draw the result.
 */

private const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val DISTANCE_DECIMALS = 1
private const val RPM_DECIMALS = 0
private val StartFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.US)

private const val NOT_CONNECTED_NOTICE = "Not connected. Connect under Setup, Connection, then press Record."
internal const val PICKER_MISSING_NOTICE =
    "This head unit has no folder picker. Use the Wi-Fi export to get the trips off the head unit."
internal const val STILL_RECORDING_NOTICE = "This trip is still recording. Stop it first."

/** "12:34", or "1:05:09" from one hour on: seconds always show, so the clock visibly runs. */
internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0) / MS_PER_SECOND
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    val minutes = totalSeconds / SECONDS_PER_MINUTE % MINUTES_PER_HOUR
    val hours = totalSeconds / (SECONDS_PER_MINUTE * MINUTES_PER_HOUR)
    return if (hours > 0) "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds) else "%02d:%02d".format(Locale.US, minutes, seconds)
}

/** Time since the trip started; a clock that is a moment behind the recorder counts as 0. */
internal fun elapsedMs(startMs: Long, nowMs: Long): Long = (nowMs - startMs).coerceAtLeast(0)

/** "21 Oct 14:05" in the head unit's time zone: the same text in the list and in the delete question. */
internal fun formatTripStart(startMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    StartFormat.format(Instant.ofEpochMilli(startMs).atZone(zone))

/** One line of the trip list, every value already formatted. */
internal data class TripRow(
    val id: Long,
    val start: String,
    val duration: String,
    val distance: String,
    val maxRpm: String,
    val isRecording: Boolean,
)

/** The trip being recorded, and one cut off by a power loss that is not repaired yet, show "--" for the summary values. */
internal fun tripRows(trips: List<Trip>, recordingFileName: String?, zone: ZoneId = ZoneId.systemDefault()): List<TripRow> =
    trips.map { trip ->
        val isRecording = trip.fileName == recordingFileName
        // The summary is written when the trip ends; until then (or after a power loss) there is none.
        val endMs = trip.endMs.takeUnless { isRecording }
        TripRow(
            id = trip.id,
            start = formatTripStart(trip.startMs, zone),
            duration = endMs?.let { formatDuration(it - trip.startMs) } ?: MISSING_VALUE,
            distance = endMs?.let { formatNumber(trip.distanceKm, DISTANCE_DECIMALS) } ?: MISSING_VALUE,
            maxRpm = trip.maxRpm?.takeIf { endMs != null }?.let { formatNumber(it, RPM_DECIMALS) } ?: MISSING_VALUE,
            isRecording = isRecording,
        )
    }

/** The question before a trip is deleted. */
internal fun deleteQuestion(row: TripRow): String = "Delete trip of ${row.start}? The CSV file is removed."

/** What to tell the user about a Record that did not start; null when nothing needs saying. */
internal fun startNotice(result: StartResult): String? = when (result) {
    StartResult.NotConnected -> NOT_CONNECTED_NOTICE
    // The recorder keeps the reason in lastError, which the page shows.
    StartResult.Started, StartResult.AlreadyRecording, is StartResult.Failed -> null
}

/** What the idle recording panel tells the user to do next. */
internal enum class RecordingHint { NOT_CONNECTED, WAITING_FOR_ENGINE, PRESS_RECORD }

internal fun recordingHint(isConnected: Boolean, isAutoRecord: Boolean): RecordingHint = when {
    !isConnected -> RecordingHint.NOT_CONNECTED
    isAutoRecord -> RecordingHint.WAITING_FOR_ENGINE
    else -> RecordingHint.PRESS_RECORD
}

/** A copy to USB in words: [summary] always, [detail] for the first thing that went wrong. */
internal data class CopyStatus(val summary: String, val detail: String?, val isError: Boolean)

internal fun copyStatus(progress: CopyProgress): CopyStatus {
    if (!progress.isFinished) {
        val position = progress.copied + progress.skipped + progress.failures.size + 1
        return CopyStatus("Copying $position of ${progress.total}…", null, isError = false)
    }
    val failed = progress.failures.size
    return when {
        progress.total == 0 -> CopyStatus("No trips to copy.", null, isError = false)
        failed > 0 -> CopyStatus(
            summary = copiedSummary(progress) + ", $failed failed.",
            detail = progress.failures.first().reason,
            isError = true,
        )
        progress.copied == 0 -> CopyStatus("Nothing new to copy: ${progress.skipped} already there.", null, isError = false)
        else -> CopyStatus(copiedSummary(progress), null, isError = false)
    }
}

private fun copiedSummary(progress: CopyProgress): String {
    val copied = "Copied ${tripCount(progress.copied)}"
    return if (progress.skipped > 0) "$copied, ${progress.skipped} already there" else copied
}

private fun tripCount(count: Int): String = if (count == 1) "1 trip" else "$count trips"
