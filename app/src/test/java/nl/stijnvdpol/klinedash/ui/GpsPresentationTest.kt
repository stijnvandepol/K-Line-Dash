package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.data.GateSpec
import nl.stijnvdpol.klinedash.data.newTrack
import nl.stijnvdpol.klinedash.service.GpsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsPresentationTest {
    private fun fix(speedMps: Double?) = GeoFix(0, 51.0, 5.0, speedMps = speedMps, accuracyM = 3.0)

    private fun track(id: Long, sectors: Int) = newTrack("Track $id", GateSpec(51.0, 5.0, 90.0), List(sectors) { GateSpec(51.0, 5.0 + it / 1000.0, 90.0) }, 1_000L + id)
        .copy(id = id)

    private fun trip(id: Long, trackId: Long?) = Trip(
        id = id, startMs = id, endMs = id + 1, fileName = "trip-$id.csv", sampleCount = 1, distanceKm = 1.0,
        maxRpm = null, maxSpeedKmh = null, maxCoolantC = null, trackId = trackId,
    )

    @Test
    fun `the mark is green with a fix, muted while searching, struck through when off`() {
        assertEquals(GpsMark(GpsMarkKind.FIX, 12), gpsMark(GpsStatus.Fix(3.0, 12, 5.0)))
        assertEquals(GpsMark(GpsMarkKind.FIX, null), gpsMark(GpsStatus.Fix(3.0, null, null)))
        assertEquals(GpsMark(GpsMarkKind.SEARCHING, null), gpsMark(GpsStatus.Searching))
        assertEquals(GpsMark(GpsMarkKind.SEARCHING, null), gpsMark(GpsStatus.Idle))
        assertEquals(GpsMark(GpsMarkKind.OFF, null), gpsMark(GpsStatus.NoPermission))
        assertEquals(GpsMark(GpsMarkKind.OFF, null), gpsMark(GpsStatus.Disabled))
    }

    @Test
    fun `a fix shows satellites, accuracy and rate`() {
        val info = gpsInfo(GpsStatus.Fix(accuracyM = 3.4, satellites = 12, rateHz = 4.96))
        assertEquals(listOf("Fix", "12", "3", "5.0"), listOf(info.state, info.satellites, info.accuracyM, info.rateHz))
        assertNull(info.hint)
    }

    @Test
    fun `without a fix the values are dashes and a hint says what to do`() {
        val missing = gpsInfo(GpsStatus.NoPermission)
        assertEquals("Not allowed", missing.state)
        assertEquals(listOf(MISSING_VALUE, MISSING_VALUE, MISSING_VALUE), listOf(missing.satellites, missing.accuracyM, missing.rateHz))
        assertTrue(checkNotNull(missing.hint).contains("permission"))
        assertEquals("Off", gpsInfo(GpsStatus.Disabled).state)
        assertEquals("Searching", gpsInfo(GpsStatus.Searching).state)
        assertEquals(MISSING_VALUE, gpsInfo(GpsStatus.Fix(null, null, null)).accuracyM)
    }

    @Test
    fun `renaming is only offered while parked`() {
        assertTrue(isParked(GpsStatus.Fix(3.0, 12, 5.0), fix(0.3)))
        assertFalse(isParked(GpsStatus.Fix(3.0, 12, 5.0), fix(15.0)))
        assertTrue(isParked(GpsStatus.Fix(3.0, 12, 5.0), fix(null)))
        assertTrue(isParked(GpsStatus.Searching, fix(30.0)))
        assertTrue(isParked(GpsStatus.NoPermission, null))
    }

    @Test
    fun `a track row says what the track holds and how many sessions used it`() {
        val rows = trackRows(
            tracks = listOf(track(2, sectors = 2), track(1, sectors = 0)),
            active = track(2, sectors = 2),
            trips = listOf(trip(1, 2), trip(2, 2), trip(3, null), trip(4, 1)),
        )
        assertEquals("Start/finish and 2 sector lines, 2 sessions", rows[0].detail)
        assertTrue(rows[0].isActive)
        assertEquals("Start/finish line, 1 session", rows[1].detail)
        assertFalse(rows[1].isActive)
    }

    @Test
    fun `a track without sessions says so`() {
        assertEquals("Start/finish and 1 sector line, no sessions yet", trackRows(listOf(track(1, sectors = 1)), null, emptyList()).single().detail)
    }

    @Test
    fun `a track name must not be empty or too long`() {
        assertNull(trackNameProblem("Spa"))
        assertEquals("Type a name for the track.", trackNameProblem("   "))
        assertEquals("Type a name for the track.", trackNameProblem(""))
        assertNull(trackNameProblem("x".repeat(MAX_TRACK_NAME_LENGTH)))
        assertEquals("Use at most $MAX_TRACK_NAME_LENGTH characters.", trackNameProblem("x".repeat(MAX_TRACK_NAME_LENGTH + 1)))
    }

    @Test
    fun `the delete question names the track and what happens to its trips`() {
        assertEquals("Delete track Spa? Its trips are kept but lose their laps.", deleteTrackQuestion("Spa"))
    }
}
