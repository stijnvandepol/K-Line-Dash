package nl.stijnvdpol.klinedash.core.trip

import nl.stijnvdpol.klinedash.core.geo.Gate
import nl.stijnvdpol.klinedash.core.geo.LapGates
import nl.stijnvdpol.klinedash.core.geo.TestLoop
import nl.stijnvdpol.klinedash.core.geo.TestWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripAnalyzerTest {

    private val t0 = TestWorld.T0
    private val gates = LapGates(TestWorld.loopGate)

    /**
     * Three laps of 48.78 s at 20.5 m/s (fix every second). Laps run about 4.9-53.7 s, 53.7-102.4 s
     * and 102.4-151.2 s. Lap 1 has a rpm peak, lap 2 a stretch at full throttle and a rpm peak,
     * lap 3 a slow moment at 5 m/s.
     */
    private fun loopSamples(): List<TripSample> = TestLoop.drive(3_300.0, 20.5).mapIndexed { second, fix ->
        val speedMps = if (second == 110) 5.0 else 20.5
        val rpm = when (second) {
            30 -> 6_000.0
            80 -> 7_000.0
            else -> 2_000.0
        }
        val throttle = if (second in 60..64) 100.0 else 10.0
        TripSample(fix.timeMs, mapOf("rpm" to rpm, "throttle" to throttle, "speed" to 70.0), fix.copy(speedMps = speedMps))
    }

    @Test
    fun withoutGatesOnlyTheTripStatsAreComputed() {
        val analyzer = TripAnalyzer()
        loopSamples().forEach(analyzer::add)

        assertNull(analyzer.timing())
        assertTrue(analyzer.lapStats().isEmpty())
        val stats = analyzer.stats()!!
        assertEquals(5_000L, stats.fullThrottleTimeMs)
        assertEquals(7_000.0, stats.maxRpm!!, 0.0)
        assertEquals(3.3, stats.distanceKm, 0.12)
    }

    @Test
    fun everyLapGetsItsOwnStatistics() {
        val analysis = analyzeTrip(loopSamples().asSequence(), gates)
        val laps = analysis.laps

        assertEquals(3, laps.size)
        assertEquals(listOf(6_000.0, 7_000.0, 2_000.0), laps.map { it.maxRpm })
        assertEquals(listOf(0L, 5_000L, 0L), laps.map { it.fullThrottleTimeMs })
        assertEquals(20.5 * 3.6, laps[0].maxSpeedKmh!!, 1e-9)
        assertEquals(20.5 * 3.6, laps[1].minSpeedKmh!!, 1e-9)
        assertEquals(5.0 * 3.6, laps[2].minSpeedKmh!!, 1e-9)
        assertEquals(1.0, laps[0].distanceKm, 0.04)
        assertEquals(20.5 * 3.6, laps[0].averageSpeedKmh!!, 2.0)
        assertEquals(laps.size, analysis.laps.count { it.lap.isValid })
    }

    @Test
    fun recordedAnalysisEqualsTheLiveOne() {
        val live = TripAnalyzer(gates)
        val samples = loopSamples()
        samples.forEach(live::add)

        val recorded = analyzeTrip(samples.asSequence(), gates)

        assertEquals(live.lapStats(), recorded.laps)
        assertEquals(live.stats(), recorded.stats)
    }

    @Test
    fun aDifferentGateRecomputesTheLapsOfTheSameTrip() {
        val samples = loopSamples().asSequence()
        val northGate = LapGates(Gate.fromPointAndHeading(TestWorld.position(300.0, 100.0), 0.0))

        val laps = analyzeTrip(samples, northGate).laps

        assertEquals(2, laps.size)
        assertEquals(48_780.0, laps[0].lap.lapTimeMs.toDouble(), 20.0)
        assertTrue(laps[0].lap.startMs > analyzeTrip(samples, gates).laps[0].lap.startMs)
    }

    @Test
    fun liveTimingIsAvailableAfterEverySample() {
        val analyzer = TripAnalyzer(gates)
        val samples = loopSamples()
        samples.take(10).forEach(analyzer::add)

        val timing = analyzer.timing()!!
        assertTrue(timing.isTiming)
        assertEquals(0, timing.lapCount)
        assertEquals(9_000.0 - 4_878.0, timing.currentLapElapsedMs!!.toDouble(), 20.0)
    }

    @Test
    fun aLapDroppedAtASourceSwitchLeavesNothingInTheNextLapsStatistics() {
        val samples = loopSamples().mapIndexed { second, sample ->
            if (second == 30) sample.copy(values = sample.values + ("rpm" to 8_000.0)) else sample
        }
        val analyzer = TripAnalyzer(gates)
        samples.take(41).forEach(analyzer::add)
        // The lap in progress (started at 4.9 s, with the 8000 rpm peak) is dropped here.
        analyzer.startNewGpsSegment()
        samples.drop(41).forEach(analyzer::add)

        val laps = analyzer.lapStats()
        assertEquals(2, laps.size)
        assertEquals(listOf(7_000.0, 2_000.0), laps.map { it.maxRpm })
        assertEquals(listOf(5_000L, 0L), laps.map { it.fullThrottleTimeMs })
    }

    @Test
    fun samplesWithoutAFixStillCountForTheTripButNotForTheDetector() {
        val analyzer = TripAnalyzer(gates)
        analyzer.add(TripSample(t0, mapOf("speed" to 50.0)))
        analyzer.add(TripSample(t0 + 1_000, mapOf("speed" to 50.0)))

        assertEquals(2, analyzer.stats()!!.sampleCount)
        assertEquals(false, analyzer.timing()!!.isTiming)
        assertTrue(analyzer.lapStats().isEmpty())
    }
}
