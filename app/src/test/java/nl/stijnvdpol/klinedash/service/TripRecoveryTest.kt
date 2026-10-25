package nl.stijnvdpol.klinedash.service

import java.io.File
import java.io.StringReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.core.trip.CsvTripWriter
import nl.stijnvdpol.klinedash.data.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TripRecoveryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val startMs = 1_792_677_787_000L

    /** A real CSV from the production writer: [seconds] rows, 1 s apart, speed 36 km/h (10 m/s). */
    private fun csv(seconds: Int): String {
        val out = StringBuilder()
        val writer = CsvTripWriter(out)
        writer.writeHeader(listOf("rpm", "speed", "coolant").map { checkNotNull(Signals.byId(it)) })
        for (i in 0 until seconds) {
            val now = startMs + i * 1_000L
            writer.writeSample(
                now,
                mapOf(
                    "rpm" to Reading("rpm", 1000.0 + i, now),
                    "speed" to Reading("speed", 36.0, now),
                    "coolant" to Reading("coolant", 90.0, now),
                ),
            )
        }
        return out.toString()
    }

    private fun lines(text: String) = text.lines().filter { it.isNotEmpty() }

    @Test
    fun completeFileGivesCountEndDistanceAndMaxima() {
        val summary = checkNotNull(recoverTripSummary(StringReader(csv(61))))
        assertEquals(61, summary.sampleCount)
        assertEquals(startMs + 60_000, summary.endMs)
        assertEquals(1060.0, checkNotNull(summary.maxRpm), 0.001)
        assertEquals(36.0, checkNotNull(summary.maxSpeedKmh), 0.001)
        assertEquals(90.0, checkNotNull(summary.maxCoolantC), 0.001)
        assertEquals(0.6, summary.distanceKm, 0.001)
    }

    @Test
    fun rowCutInTheMiddleIsDropped() {
        val cut = csv(10).dropLast(7) // loses the line feed and part of the last row
        val summary = checkNotNull(recoverTripSummary(StringReader(cut)))
        assertEquals(9, summary.sampleCount)
        assertEquals(startMs + 8_000, summary.endMs)
    }

    @Test
    fun headerOnlyOrEmptyFilesHaveNothingToRecover() {
        assertNull(recoverTripSummary(StringReader("")))
        assertNull(recoverTripSummary(StringReader(csv(0))))
        assertNull(recoverTripSummary(StringReader("\uFEFFtime_iso,elapsed_s")))
    }

    @Test
    fun rowWithAnUnreadableTimeIsSkipped() {
        val broken = lines(csv(3)).toMutableList().also { it[2] = "garbage,1.0,1000,36,90" }
        assertEquals(2, recoverTripSummary(StringReader(broken.joinToString("\n", postfix = "\n")))?.sampleCount)
    }

    @Test
    fun aFileCutInTheMiddleOfARowIsRecoveredFromDiskWithoutThatRow() = runBlocking {
        val dao = FakeTripDao()
        val dir = folder.newFolder("trips")
        File(dir, "cut.csv").writeText("\uFEFF" + csv(10).dropLast(7), Charsets.UTF_8)
        val id = dao.insert(trip("cut.csv"))

        TripRecovery(dir, dao, Dispatchers.Unconfined).recover(activeTripId = null)

        assertEquals(9, checkNotNull(dao.get(id)).sampleCount)
    }

    @Test
    fun recoveryFinishesUnfinishedTripsAndRemovesEmptyOnes() = runBlocking {
        val dao = FakeTripDao()
        val dir = folder.newFolder("trips")
        File(dir, "good.csv").writeText("\uFEFF" + csv(31))
        File(dir, "empty.csv").writeText("\uFEFF")
        val good = dao.insert(trip("good.csv"))
        val empty = dao.insert(trip("empty.csv"))
        val missing = dao.insert(trip("missing.csv"))
        val active = dao.insert(trip("good.csv"))

        TripRecovery(dir, dao, Dispatchers.Unconfined).recover(activeTripId = active)

        val finished = checkNotNull(dao.get(good))
        assertEquals(31, finished.sampleCount)
        assertEquals(startMs + 30_000, finished.endMs)
        assertNull(dao.get(empty))
        assertNull(dao.get(missing))
        assertFalse(File(dir, "empty.csv").exists())
        assertNull("the trip being recorded is left alone", dao.get(active)?.endMs)
        assertNotNull(dao.get(active))
        assertTrue(File(dir, "good.csv").exists())
    }

    private fun trip(fileName: String) = Trip(
        startMs = startMs, endMs = null, fileName = fileName, sampleCount = 0,
        distanceKm = 0.0, maxRpm = null, maxSpeedKmh = null, maxCoolantC = null,
    )

    @Test
    fun recoveredTripWithPositionsUsesGpsDistanceAndKnowsItHasGps() {
        val text = tripCsvOf(circuitFixes(speedKmh = 72.0, durationMs = 60_000))
        val recovered = checkNotNull(recoverTrip(StringReader(text)))
        assertTrue(recovered.hasGps)
        assertEquals("1.2 km at 20 m/s, taken from the fixes", 1.2, recovered.stats.gpsDistanceKm, 0.02)
        assertFalse(checkNotNull(recoverTrip(StringReader(csv(5)))).hasGps)
    }
}
