package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.service.CopyFailure
import nl.stijnvdpol.klinedash.service.CopyProgress
import nl.stijnvdpol.klinedash.service.StartResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TripPresentationTest {
    private val zone = ZoneId.of("Europe/Amsterdam")

    private fun epochMs(day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun trip(
        id: Long = 1,
        startMs: Long = epochMs(21, 14, 5),
        endMs: Long? = startMs + 25 * 60_000,
        fileName: String = "trip-$id.csv",
        distanceKm: Double = 12.34,
        maxRpm: Double? = 6_850.0,
    ) = Trip(
        id = id, startMs = startMs, endMs = endMs, fileName = fileName, sampleCount = 1_500,
        distanceKm = distanceKm, maxRpm = maxRpm, maxSpeedKmh = 120.0, maxCoolantC = 95.0,
    )

    private fun progress(
        total: Int = 3,
        copied: Int = 0,
        skipped: Int = 0,
        failures: List<CopyFailure> = emptyList(),
        isFinished: Boolean = true,
    ) = CopyProgress(total, copied, skipped, failures, currentFileName = null, isFinished = isFinished)

    @Test
    fun `a duration reads minutes and seconds under an hour`() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("00:09", formatDuration(9_999))
        assertEquals("12:34", formatDuration(754_000))
        assertEquals("59:59", formatDuration(3_599_000))
    }

    @Test
    fun `a duration shows hours from one hour on`() {
        assertEquals("1:00:00", formatDuration(3_600_000))
        assertEquals("1:05:09", formatDuration(3_909_000))
        assertEquals("12:00:01", formatDuration(43_201_000))
    }

    @Test
    fun `a negative duration counts as zero`() {
        assertEquals("00:00", formatDuration(-5_000))
        assertEquals(0, elapsedMs(startMs = 10_000, nowMs = 9_000))
        assertEquals(4_000, elapsedMs(startMs = 10_000, nowMs = 14_000))
    }

    @Test
    fun `a trip start shows day, month and time in the given zone`() {
        assertEquals("21 Oct 14:05", formatTripStart(epochMs(21, 14, 5), zone))
        assertEquals("3 Oct 09:07", formatTripStart(epochMs(3, 9, 7), zone))
    }

    @Test
    fun `a finished trip shows its summary in fixed formats`() {
        val row = tripRows(listOf(trip()), recordingFileName = null, zone).single()
        assertEquals("21 Oct 14:05", row.start)
        assertEquals("25:00", row.duration)
        assertEquals("12.3", row.distance)
        assertEquals("6850", row.maxRpm)
        assertFalse(row.isRecording)
    }

    @Test
    fun `the trip being recorded has no summary yet`() {
        val running = trip(id = 2, endMs = null, distanceKm = 0.0, maxRpm = null, fileName = "trip-running.csv")
        val rows = tripRows(listOf(running, trip(id = 1)), recordingFileName = "trip-running.csv", zone)
        assertTrue(rows[0].isRecording)
        assertEquals(listOf(MISSING_VALUE, MISSING_VALUE, MISSING_VALUE), listOf(rows[0].duration, rows[0].distance, rows[0].maxRpm))
        assertFalse(rows[1].isRecording)
    }

    @Test
    fun `a trip cut off by a power loss shows dashes for what is unknown`() {
        val cutOff = trip(endMs = null, maxRpm = null)
        val row = tripRows(listOf(cutOff), recordingFileName = null, zone).single()
        assertFalse(row.isRecording)
        assertEquals(MISSING_VALUE, row.duration)
        assertEquals(MISSING_VALUE, row.distance)
        assertEquals(MISSING_VALUE, row.maxRpm)
    }

    @Test
    fun `a trip without any rpm shows a dash`() {
        assertEquals(MISSING_VALUE, tripRows(listOf(trip(maxRpm = null)), null, zone).single().maxRpm)
    }

    @Test
    fun `the order of the list is kept`() {
        val rows = tripRows(listOf(trip(id = 3), trip(id = 2), trip(id = 1)), null, zone)
        assertEquals(listOf(3L, 2L, 1L), rows.map { it.id })
    }

    @Test
    fun `the delete question names the trip and the file`() {
        val row = tripRows(listOf(trip()), null, zone).single()
        assertEquals("Delete trip of 21 Oct 14:05? The CSV file is removed.", deleteQuestion(row))
    }

    @Test
    fun `only a missing link needs a notice when Record is refused`() {
        assertEquals(true, startNotice(StartResult.NotConnected)?.contains("Not connected"))
        assertNull(startNotice(StartResult.Started))
        assertNull(startNotice(StartResult.AlreadyRecording))
        assertNull(startNotice(StartResult.Failed("Could not create the trip file.")))
    }

    @Test
    fun `the idle panel says what to do next`() {
        assertEquals(RecordingHint.NOT_CONNECTED, recordingHint(isConnected = false, isAutoRecord = true))
        assertEquals(RecordingHint.WAITING_FOR_ENGINE, recordingHint(isConnected = true, isAutoRecord = true))
        assertEquals(RecordingHint.PRESS_RECORD, recordingHint(isConnected = true, isAutoRecord = false))
    }

    @Test
    fun `a copy reports how far it is`() {
        val status = copyStatus(progress(total = 3, copied = 1, skipped = 0, isFinished = false))
        assertEquals("Copying 2 of 3…", status.summary)
        assertFalse(status.isError)
    }

    @Test
    fun `a finished copy says what was copied and what was already there`() {
        assertEquals("Copied 3 trips, 1 already there", copyStatus(progress(total = 4, copied = 3, skipped = 1)).summary)
        assertEquals("Copied 3 trips", copyStatus(progress(total = 3, copied = 3)).summary)
        assertEquals("Copied 1 trip", copyStatus(progress(total = 1, copied = 1)).summary)
    }

    @Test
    fun `a copy with nothing new says so`() {
        assertEquals("Nothing new to copy: 2 already there.", copyStatus(progress(total = 2, skipped = 2)).summary)
        assertEquals("No trips to copy.", copyStatus(progress(total = 0)).summary)
    }

    @Test
    fun `a copy with failures is an error that names the first reason`() {
        val failures = listOf(CopyFailure("a.csv", "Could not write the file."), CopyFailure("b.csv", "The trip file is missing."))
        val status = copyStatus(progress(total = 3, copied = 1, failures = failures))
        assertEquals("Copied 1 trip, 2 failed.", status.summary)
        assertEquals("Could not write the file.", status.detail)
        assertTrue(status.isError)
    }
}
