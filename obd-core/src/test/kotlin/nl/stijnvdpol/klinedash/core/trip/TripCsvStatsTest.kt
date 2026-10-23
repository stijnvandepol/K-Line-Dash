package nl.stijnvdpol.klinedash.core.trip

import java.io.StringReader
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.obd.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The statistics that need the air flow (fuel) and the throttle (full-throttle time) must come out
 * of a re-read trip file exactly as they did live, and a file without those columns must still read.
 */
class TripCsvStatsTest {

    private val columns = listOf("rpm", "speed", "throttle", "maf").map { checkNotNull(Signals.byId(it)) }

    /** 2026-10-22T14:03:07.250Z */
    private val t0 = 1_792_677_787_250L

    /** Values the columns hold without rounding (whole rpm, km/h and percent, one decimal of g/s). */
    private fun readingsAt(second: Int): Map<String, Reading> {
        val now = t0 + second * 1_000L
        val values = mapOf(
            "rpm" to 2_500.0 + second * 100,
            "speed" to 40.0 + second,
            "throttle" to if (second in 3..5) 100.0 else 25.0,
            "maf" to 8.0 + second * 0.5,
        )
        return values.mapValues { (id, value) -> Reading(id, value, now) }
    }

    private fun writtenTrip(seconds: IntRange): String {
        val out = StringBuilder()
        val csv = CsvTripWriter(out)
        csv.writeHeader(columns)
        for (second in seconds) csv.writeSample(t0 + second * 1_000L, readingsAt(second))
        return out.toString()
    }

    private fun statsOf(samples: Sequence<TripSample>): TripStats {
        val accumulator = TripStatsAccumulator()
        samples.forEach(accumulator::add)
        return checkNotNull(accumulator.stats())
    }

    @Test
    fun airFlowAndThrottleAreColumnsNamedLikeTheOthers() {
        val header = writtenTrip(0..0).lines().first()

        assertEquals("time_iso,elapsed_s,rpm (rpm),speed (km/h),throttle (%),maf (g/s)", header)
    }

    @Test
    fun theStatisticsOfAReadFileEqualTheLiveOnes() {
        val seconds = 0..9
        val live = statsOf(seconds.asSequence().map { tripSampleOf(t0 + it * 1_000L, readingsAt(it)) })

        val reread = statsOf(readTripCsv(StringReader(writtenTrip(seconds))).samples)

        assertEquals(live, reread)
        assertNotNull("fuel needs the air flow column", reread.estimatedFuelLiters)
        assertEquals("seconds 3 to 5 are above 90 % throttle, each held for one second", 3_000L, reread.fullThrottleTimeMs)
    }

    @Test
    fun aFileWithoutAirFlowAndThrottleColumnsStillReadsAndHasNoFuelOrFullThrottle() {
        val oldFile = "time_iso,elapsed_s,rpm (rpm),speed (km/h)\n" +
            "2026-10-22T14:03:07.250Z,0.0,2500,40\n" +
            "2026-10-22T14:03:08.250Z,1.0,2600,41\n"

        val csv = readTripCsv(StringReader(oldFile))
        val stats = statsOf(csv.samples)

        assertEquals(listOf("rpm", "speed"), csv.columns.map { it.id })
        assertEquals(2, stats.sampleCount)
        assertNull(stats.estimatedFuelLiters)
        assertEquals(0L, stats.fullThrottleTimeMs)
        assertTrue(stats.maxRpm == 2_600.0)
    }
}
