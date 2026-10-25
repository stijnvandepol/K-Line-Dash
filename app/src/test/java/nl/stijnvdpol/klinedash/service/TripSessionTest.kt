package nl.stijnvdpol.klinedash.service

import java.io.File
import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.trip.analyzeTrip
import nl.stijnvdpol.klinedash.core.trip.readTripCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TripSessionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val startMs = 100_000L

    private fun readings(now: Long, vararg ids: String) =
        ids.associateWith { Reading(it, 50.0, now) }

    /** The monotonic clock of the session; [addRow] moves it with the sample time unless a test sets it. */
    private var elapsedMs = 0L

    private fun newSession(settleMs: Long = 20_000, flushEvery: Int = 10): TripSession =
        TripSession(File(folder.root, "t.csv"), { elapsedMs }, settleMs = settleMs, flushEverySamples = flushEvery)

    private fun TripSession.addRow(nowMs: Long, readings: Map<String, Reading>, gps: GeoFix? = null) {
        elapsedMs = nowMs - startMs
        add(nowMs, readings, gps)
    }

    /** Adds a row taken [elapsedAfterMs] after the start on the monotonic clock, whatever the wall clock says. */
    private fun TripSession.addAfter(elapsedAfterMs: Long, wallMs: Long) {
        elapsedMs = elapsedAfterMs
        add(wallMs, readings(wallMs, "rpm"))
    }

    private fun text() = File(folder.root, "t.csv").readText(Charsets.UTF_8)

    @Test
    fun fileIsUtf8WithByteOrderMark() {
        newSession().use { it.addRow(startMs, readings(startMs, "rpm")) }
        val bytes = File(folder.root, "t.csv").readBytes()
        assertEquals(listOf(0xEF, 0xBB, 0xBF), bytes.take(3).map { it.toInt() and 0xFF })
        assertTrue(text().removePrefix("\uFEFF").startsWith("time_iso,elapsed_s,"))
    }

    @Test
    fun headerWaitsForTheSettleTimeAndUsesEverySignalSeenUntilThen() {
        newSession().use { session ->
            session.addRow(startMs, readings(startMs, "rpm"))
            session.addRow(startMs + 10_000, readings(startMs + 10_000, "rpm", "battery"))
            assertEquals("nothing is on disk before the header", "", text())
            session.addRow(startMs + 20_000, readings(startMs + 20_000, "rpm", "battery", "load"))
            val header = text().lines().first()
            assertTrue(header, header.contains("rpm (rpm)") && header.contains("battery") && header.contains("load"))
            assertTrue("forced summary columns are always there", header.contains("speed") && header.contains("coolant"))
            assertEquals("header and the three kept samples", 4, text().lines().count { it.isNotEmpty() })
        }
    }

    @Test
    fun theAirFlowAndThrottleColumnsAreAlwaysThereBecauseTheStatisticsNeedThem() {
        newSession().use { it.addRow(startMs, readings(startMs, "rpm")) }
        val header = text().lines().first()
        assertTrue(header, "maf (g/s)" in header && "throttle (%)" in header)
    }

    @Test
    fun fuelAndFullThrottleThatAppearAfterTheHeaderAreInTheFileAndReadBackLikeTheLiveOnes() {
        val session = newSession()
        for (second in 0..40) {
            val now = startMs + second * 1_000L
            // The signals only a screen polls show up once the header is long written.
            val ids = if (second < 30) arrayOf("rpm", "speed", "coolant") else arrayOf("rpm", "speed", "coolant", "maf", "throttle")
            val values = readings(now, *ids).mapValues { (id, reading) ->
                reading.copy(value = if (id == "throttle") 100.0 else if (id == "maf") 10.0 else reading.value)
            }
            session.addRow(now, values)
        }
        session.close()
        val live = checkNotNull(session.liveState().stats)

        val reread = File(folder.root, "t.csv").bufferedReader(Charsets.UTF_8).use { reader ->
            analyzeTrip(readTripCsv(reader).samples).stats
        }

        assertTrue("the live statistics saw the fuel", checkNotNull(live.estimatedFuelLiters) > 0.0)
        assertEquals(live.estimatedFuelLiters, checkNotNull(reread).estimatedFuelLiters)
        assertEquals(live.fullThrottleTimeMs, reread.fullThrottleTimeMs)
    }

    @Test
    fun aWallClockThatStepsBackDoesNotKeepTheHeaderFromBeingWritten() {
        newSession().use { session ->
            session.addAfter(0, startMs)
            session.addAfter(10_000, startMs - 3 * 3_600_000L) // boot: the clock was set back by hours
            assertEquals("not settled yet", "", text())
            session.addAfter(20_000, startMs - 3 * 3_600_000L + 10_000)
            assertTrue("the header is written after 20 s of monotonic time", text().startsWith("\uFEFFtime_iso"))
        }
    }

    @Test
    fun aWallClockThatStepsForwardDoesNotWriteTheHeaderAfterOneSample() {
        newSession().use { session ->
            session.addAfter(0, startMs)
            session.addAfter(1_000, startMs + 5 * 3_600_000L) // the clock was set forward by hours
            assertEquals("one second in, the signals are not known yet", "", text())
        }
    }

    @Test
    fun closingBeforeTheSettleTimeStillWritesTheKeptSamples() {
        val session = newSession()
        session.addRow(startMs, readings(startMs, "rpm"))
        session.addRow(startMs + 1_000, readings(startMs + 1_000, "rpm"))
        session.close()
        assertEquals(3, text().lines().count { it.isNotEmpty() })
        assertEquals(2, checkNotNull(session.summary()).sampleCount)
    }

    @Test
    fun rowsReachTheDiskEveryFlushInterval() {
        newSession(settleMs = 0, flushEvery = 3).use { session ->
            session.addRow(startMs, readings(startMs, "rpm")) // header + row, flushed
            val afterFirst = text().lines().count { it.isNotEmpty() }
            session.addRow(startMs + 1_000, readings(startMs + 1_000, "rpm"))
            session.addRow(startMs + 2_000, readings(startMs + 2_000, "rpm"))
            assertEquals("two rows still buffered", afterFirst, text().lines().count { it.isNotEmpty() })
            session.addRow(startMs + 3_000, readings(startMs + 3_000, "rpm"))
            assertEquals(afterFirst + 3, text().lines().count { it.isNotEmpty() })
        }
    }

    @Test
    fun summaryHasNoValueBeforeTheFirstSample() {
        newSession().use { assertEquals(null, it.summary()) }
    }

    @Test
    fun rowsCarryTheFixAndTheLiveStateSeesEverySampleAtOnce() {
        val fixes = circuitFixes(speedKmh = 72.0, durationMs = 5_000, startMs = startMs)
        newSession(settleMs = 20_000).use { session ->
            fixes.forEach { session.addRow(it.timeMs, readings(it.timeMs, "rpm"), it) }
            assertEquals("the header is still waiting", "", text())
            val stats = checkNotNull(session.liveState().stats)
            assertEquals(fixes.size, stats.sampleCount)
            assertTrue(stats.gpsDistanceKm > 0.09)
            assertTrue(session.cache().hasGps)
        }
        val lines = text().lines().filter { it.isNotEmpty() }
        assertEquals(fixes.size + 1, lines.size)
        assertTrue(lines.first().contains("lat (deg),lon (deg)"))
        assertTrue("latitude and longitude are in the row", lines[1].split(",").takeLast(7).take(2).all { it.isNotEmpty() })
    }

    @Test
    fun aTrackSetMidTripStartsLapTimingFromThen() {
        newSession().use { session ->
            session.addRow(startMs, readings(startMs, "rpm"))
            assertEquals(null, session.liveState().lapTiming)
            session.setTrack(5, circuitTrack().lapGates())
            session.addRow(startMs + 1_000, readings(startMs + 1_000, "rpm"))
            assertEquals(5L, session.liveState().trackId)
            assertEquals(false, checkNotNull(session.liveState().lapTiming).isTiming)
            assertEquals(0, session.cache().lapCount)
        }
    }

    @Test
    fun theLapSessionRemembersTheLastSampleBeforeItStarted() {
        newSession().use { session ->
            session.setTrack(5, circuitTrack().lapGates())
            assertEquals("a line set before any sample times the whole trip", null, session.lapTimingAfterMs)

            session.setTrack(null, null)
            session.addRow(startMs, readings(startMs, "rpm"))
            session.addRow(startMs + 1_000, readings(startMs + 1_000, "rpm"))
            session.setTrack(6, circuitTrack().lapGates())
            assertEquals(startMs + 1_000, session.lapTimingAfterMs)

            session.addRow(startMs + 2_000, readings(startMs + 2_000, "rpm"))
            val withSector = circuitTrack(sectors = listOf(circuitStartLine())).lapGates()
            session.setTrack(6, withSector)
            assertEquals("a sector line added on the same track does not restart the session", startMs + 1_000, session.lapTimingAfterMs)
        }
    }
}
