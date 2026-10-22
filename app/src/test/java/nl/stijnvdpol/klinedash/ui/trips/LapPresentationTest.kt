package nl.stijnvdpol.klinedash.ui.trips

import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.geo.Lap
import nl.stijnvdpol.klinedash.core.geo.LapTimingState
import nl.stijnvdpol.klinedash.core.trip.LapStats
import nl.stijnvdpol.klinedash.data.Track
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.service.GpsStatus
import nl.stijnvdpol.klinedash.service.circuitTrack
import nl.stijnvdpol.klinedash.ui.MISSING_VALUE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LapPresentationTest {
    private fun lap(number: Int, timeMs: Long, isValid: Boolean = true, sectors: List<Long> = emptyList()) =
        Lap(number, startMs = 0, endMs = timeMs, sectorTimesMs = sectors, distanceM = 2_000.0, isValid = isValid)

    private fun stats(
        lap: Lap,
        maxSpeedKmh: Double? = 150.0,
        maxRpm: Double? = 7_200.0,
        fullThrottleTimeMs: Long = 0,
    ) = LapStats(lap, maxSpeedKmh, minSpeedKmh = 60.0, maxRpm = maxRpm, fullThrottleTimeMs = fullThrottleTimeMs)

    private fun timing(
        isTiming: Boolean = true,
        elapsedMs: Long? = 83_456,
        splitsMs: List<Long> = emptyList(),
        last: Lap? = null,
        best: Lap? = null,
        lapCount: Int = 0,
        deltaMs: Long? = null,
    ) = LapTimingState(isTiming, elapsedMs, splitsMs, last, best, lapCount, deltaMs, predictedLapMs = null)

    private fun trip(id: Long, trackId: Long?) = Trip(
        id = id, startMs = id, endMs = id + 1, fileName = "trip-$id.csv", sampleCount = 1, distanceKm = 1.0,
        maxRpm = null, maxSpeedKmh = null, maxCoolantC = null, trackId = trackId,
    )

    @Test
    fun `a lap time reads minutes, seconds and milliseconds`() {
        assertEquals("0:00.000", formatLapTime(0))
        assertEquals("1:23.456", formatLapTime(83_456))
        assertEquals("0:09.050", formatLapTime(9_050))
        assertEquals("10:00.001", formatLapTime(600_001))
    }

    @Test
    fun `a negative lap time counts as zero`() {
        assertEquals("0:00.000", formatLapTime(-5))
        assertEquals("0:00.0", formatLiveLapTime(-5))
    }

    @Test
    fun `the running lap shows tenths and never rounds up`() {
        assertEquals("1:23.4", formatLiveLapTime(83_456))
        assertEquals("1:23.9", formatLiveLapTime(83_999))
        assertEquals("0:00.0", formatLiveLapTime(99))
        assertEquals("0:00.1", formatLiveLapTime(100))
    }

    @Test
    fun `a delta has a sign and two decimals`() {
        assertEquals("+0.43", formatDelta(430))
        assertEquals("-0.21", formatDelta(-210))
        assertEquals("+12.00", formatDelta(12_000))
        assertEquals("-1.05", formatDelta(-1_050))
    }

    @Test
    fun `a delta under half a hundredth is level and has no sign`() {
        assertEquals("0.00", formatDelta(0))
        assertEquals("0.00", formatDelta(4))
        assertEquals("0.00", formatDelta(-4))
        assertEquals("+0.01", formatDelta(5))
    }

    @Test
    fun `ahead is green, behind is red, level and unknown are white`() {
        assertEquals(DeltaTone.FASTER, deltaTone(-210))
        assertEquals(DeltaTone.SLOWER, deltaTone(430))
        assertEquals(DeltaTone.EVEN, deltaTone(3))
        assertEquals(DeltaTone.EVEN, deltaTone(null))
    }

    @Test
    fun `the panel of a lap in progress shows the running time, the best lap and the delta`() {
        val best = lap(1, 82_000)
        val panel = lapPanel(timing(elapsedMs = 41_250, last = lap(2, 84_100), best = best, lapCount = 2, deltaMs = -210), sectorGateCount = 0)
        assertEquals("0:41.2", panel.current)
        assertEquals("1:24.100", panel.last)
        assertEquals("1:22.000", panel.best)
        assertEquals("-0.21", panel.delta)
        assertEquals(DeltaTone.FASTER, panel.deltaTone)
        assertEquals(2, panel.lapCount)
        assertTrue(panel.splits.isEmpty())
    }

    @Test
    fun `the panel has dashes before the first crossing and without a timer`() {
        val before = lapPanel(timing(isTiming = false, elapsedMs = null), 0)
        assertEquals(listOf(MISSING_VALUE, MISSING_VALUE, MISSING_VALUE, MISSING_VALUE), listOf(before.current, before.last, before.best, before.delta))
        assertEquals(DeltaTone.EVEN, before.deltaTone)
        val none = lapPanel(null, 0)
        assertEquals(MISSING_VALUE, none.current)
        assertEquals(0, none.lapCount)
    }

    @Test
    fun `every sector gate gets a split, crossed ones with the time`() {
        val panel = lapPanel(timing(splitsMs = listOf(31_200)), sectorGateCount = 2)
        assertEquals(listOf(SectorSplit("S1", "0:31.2"), SectorSplit("S2", MISSING_VALUE)), panel.splits)
    }

    @Test
    fun `setting the line is allowed with an accurate fix while moving`() {
        val fix = GeoFix(0, 51.0, 5.0, speedMps = 20.0, bearingDeg = 90.0, accuracyM = 4.0)
        assertNull(gateBlockReason(GpsStatus.Fix(4.0, 12, 5.0), fix))
    }

    @Test
    fun `the reason follows the GPS status`() {
        assertEquals(BLOCK_NO_PERMISSION, gateBlockReason(GpsStatus.NoPermission, null))
        assertEquals(BLOCK_DISABLED, gateBlockReason(GpsStatus.Disabled, null))
        assertEquals(BLOCK_WAITING, gateBlockReason(GpsStatus.Searching, null))
        assertEquals(BLOCK_WAITING, gateBlockReason(GpsStatus.Idle, null))
    }

    @Test
    fun `a fix that is not accurate enough or too slow says why`() {
        val inaccurate = GeoFix(0, 51.0, 5.0, speedMps = 20.0, bearingDeg = 90.0, accuracyM = 22.0)
        assertTrue(checkNotNull(gateBlockReason(GpsStatus.Fix(22.0, 8, 1.0), inaccurate)).contains("22 m"))
        val standing = GeoFix(0, 51.0, 5.0, speedMps = 0.2, bearingDeg = 90.0, accuracyM = 3.0)
        assertTrue(checkNotNull(gateBlockReason(GpsStatus.Fix(3.0, 12, 1.0), standing)).contains("direction"))
    }

    @Test
    fun `a new track is named after the moment it was set`() {
        assertTrue(defaultTrackName(1_792_677_787_000L).startsWith("Circuit "))
    }

    @Test
    fun `the last used track is the active one, else the newest lap trip's, else none`() {
        val tracks: List<Track> = listOf(circuitTrack(1), circuitTrack(2))
        assertEquals(2L, lastUsedTrack(circuitTrack(2), listOf(trip(9, trackId = 1)), tracks)?.id)
        assertEquals(1L, lastUsedTrack(null, listOf(trip(9, trackId = null), trip(8, trackId = 1)), tracks)?.id)
        assertNull(lastUsedTrack(null, listOf(trip(9, trackId = null)), tracks))
        assertNull(lastUsedTrack(null, listOf(trip(9, trackId = 5)), tracks))
    }

    @Test
    fun `the table marks the best lap, the others show the difference to it`() {
        val rows = lapRows(listOf(stats(lap(1, 84_000)), stats(lap(2, 82_500)), stats(lap(3, 83_000))))
        assertEquals(listOf(LapMark.NONE, LapMark.BEST, LapMark.NONE), rows.map { it.mark })
        assertEquals(listOf("+1.50", MISSING_VALUE, "+0.50"), rows.map { it.delta })
        assertEquals("1:22.500", rows[1].time)
    }

    @Test
    fun `a lap with a GPS dropout is never the best and shows no delta`() {
        val rows = lapRows(listOf(stats(lap(1, 70_000, isValid = false)), stats(lap(2, 83_000))))
        assertEquals(listOf(LapMark.INVALID, LapMark.BEST), rows.map { it.mark })
        assertEquals(MISSING_VALUE, rows[0].delta)
    }

    @Test
    fun `full throttle is a share of the lap and a dash without OBD data`() {
        val rows = lapRows(
            listOf(
                stats(lap(1, 80_000), fullThrottleTimeMs = 20_000),
                stats(lap(2, 80_000), maxSpeedKmh = 90.0, maxRpm = null),
            ),
        )
        assertEquals("25", rows[0].fullThrottle)
        assertEquals(MISSING_VALUE, rows[1].fullThrottle)
        assertEquals(MISSING_VALUE, rows[1].maxRpm)
        assertEquals("90", rows[1].maxSpeed)
    }

    @Test
    fun `the sector times of a lap are listed in lap time format`() {
        val rows = lapRows(listOf(stats(lap(1, 83_000, sectors = listOf(31_200, 28_100, 23_700)))))
        assertEquals(listOf("0:31.200", "0:28.100", "0:23.700"), rows.single().sectors)
    }

    @Test
    fun `no laps give no rows`() {
        assertTrue(lapRows(emptyList()).isEmpty())
    }
}
