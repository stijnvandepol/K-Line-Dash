package nl.stijnvdpol.klinedash.service

import nl.stijnvdpol.klinedash.core.trip.analyzeTrip
import nl.stijnvdpol.klinedash.core.trip.readTripCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LapTraceTest {
    private val gates = circuitTrack().lapGates()

    /** The laps and the samples of three laps at 72 km/h (100 s each); the lap detector finds three complete laps in them. */
    private fun driven(durationMs: Long = 320_000, speedKmh: Double = 72.0, withGps: Boolean = true) =
        tripCsvOf(circuitFixes(speedKmh, durationMs), withGps)

    @Test
    fun `a lap trace holds only the samples of that lap`() {
        val csv = readTripCsv(driven().reader())
        val lap = analyzeTrip(csv.samples, gates).laps.first().lap
        val trace = lapTraceOf(readTripCsv(driven().reader()).samples, lap)
        // 100 s at 5 fixes a second; the lap contains its start, not its end.
        assertEquals(500, trace.points.size)
        assertEquals(lap.lapTimeMs, trace.lapTimeMs)
        assertTrue(trace.points.all { it.elapsedMs in 0 until lap.lapTimeMs })
        assertTrue("the first fix comes within one fix interval of the start", trace.points.first().elapsedMs < 200)
        assertTrue("the last fix within one interval of the end", lap.lapTimeMs - trace.points.last().elapsedMs <= 200)
    }

    @Test
    fun `speed comes from the GPS and rpm from the OBD reading`() {
        val lap = analyzeTrip(readTripCsv(driven().reader()).samples, gates).laps.first().lap
        val point = lapTraceOf(readTripCsv(driven().reader()).samples, lap).points[10]
        assertEquals(72.0, checkNotNull(point.speedKmh), 0.5)
        assertEquals(3_000.0, checkNotNull(point.rpm), 0.01)
    }

    @Test
    fun `the route of a lap follows the circuit`() {
        val lap = analyzeTrip(readTripCsv(driven().reader()).samples, gates).laps.first().lap
        val route = lapTraceOf(readTripCsv(driven().reader()).samples, lap).route
        assertTrue(route.size in 20..MAX_ROUTE_POINTS)
        assertEquals(route.first().lat, route.last().lat, 0.001)
    }

    @Test
    fun `a slow lap is thinned to the point limit and keeps its last point`() {
        // 20 km/h: a lap takes 360 s, 1800 samples.
        val csv = tripCsvOf(circuitFixes(speedKmh = 20.0, durationMs = 800_000))
        val lap = analyzeTrip(readTripCsv(csv.reader()).samples, gates).laps.first().lap
        val trace = lapTraceOf(readTripCsv(csv.reader()).samples, lap)
        assertTrue(trace.points.size <= MAX_TRACE_POINTS)
        assertTrue(lap.lapTimeMs - trace.points.last().elapsedMs <= 200)
        assertTrue(trace.points.zipWithNext().all { (a, b) -> a.elapsedMs < b.elapsedMs })
    }

    @Test
    fun `a stray fix inside a lap is not part of its route`() {
        val fixes = circuitFixes(72.0, 320_000)
        val stray = fixes[150].copy(lat = fixes[150].lat + 0.4)
        val csv = tripCsvOf(fixes.take(150) + stray + fixes.drop(151))
        val lap = analyzeTrip(readTripCsv(csv.reader()).samples, gates).laps.first().lap

        val route = lapTraceOf(readTripCsv(csv.reader()).samples, lap).route

        assertTrue(route.size > 20)
        assertTrue("no point 40 km away", route.all { it.lat < testCircuit.pointAt(0.0).first.lat + 0.05 })
    }

    @Test
    fun `a trip without GPS has no lap trace`() {
        val lap = analyzeTrip(readTripCsv(driven().reader()).samples, gates).laps.first().lap
        val trace = lapTraceOf(readTripCsv(driven(withGps = false).reader()).samples, lap)
        assertTrue(trace.points.isEmpty())
        assertTrue(trace.route.isEmpty())
    }
}
