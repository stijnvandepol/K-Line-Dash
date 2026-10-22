package nl.stijnvdpol.klinedash.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LapDetectorTest {

    private val t0 = TestWorld.T0
    private val gates = LapGates(TestWorld.loopGate)
    private val sectorGates = LapGates(TestWorld.loopGate, listOf(TestWorld.loopSectorGate))

    /** The gate is 100 m into the loop, so the start crossing at 20.5 m/s is at 4.878 s. */
    private val loopAtSpeed = TestLoop.drive(3_300.0, 20.5)

    private fun fix(timeMs: Long, east: Double, north: Double, accuracyM: Double? = null) =
        TestWorld.fix(timeMs, east, north, accuracyM = accuracyM)

    @Test
    fun threeLapsAreFoundWithInterpolatedTimes() {
        val laps = detectLaps(loopAtSpeed, gates)

        assertEquals(listOf(1, 2, 3), laps.map { it.number })
        assertEquals(t0 + 4_878.0, laps[0].startMs.toDouble(), 20.0)
        for (lap in laps) {
            assertEquals(48_780.0, lap.lapTimeMs.toDouble(), 20.0)
            assertEquals(1_000.0, lap.distanceM, 30.0)
            assertTrue(lap.isValid)
            assertTrue(lap.sectorTimesMs.isEmpty())
        }
        assertEquals(laps[0].endMs, laps[1].startMs)
    }

    @Test
    fun driveBeforeTheFirstCrossingIsNoLap() {
        assertTrue(detectLaps(TestLoop.drive(900.0, 20.5), gates).isEmpty())
    }

    @Test
    fun drivingTheLoopTheOtherWayFindsNoLap() {
        val clockwise = (0..160).map { step ->
            val (east, north) = TestLoop.positionAt(10 * TestLoop.LENGTH_M - step * 20.5)
            fix(t0 + step * 1_000L, east, north)
        }
        assertTrue(detectLaps(clockwise, gates).isEmpty())
    }

    @Test
    fun batchResultEqualsTheIncrementalResult() {
        val detector = LapDetector(sectorGates)
        val incremental = loopAtSpeed.mapNotNull(detector::add)
        assertEquals(incremental, detectLaps(loopAtSpeed, sectorGates))
        assertEquals(3, incremental.size)
    }

    @Test
    fun sectorTimesSumToTheLapTime() {
        val laps = detectLaps(loopAtSpeed, sectorGates)
        assertEquals(3, laps.size)
        for (lap in laps) {
            assertEquals(2, lap.sectorTimesMs.size)
            assertEquals(lap.lapTimeMs, lap.sectorTimesMs.sum())
            assertEquals(300.0 / 20.5 * 1_000, lap.sectorTimesMs[0].toDouble(), 30.0)
            assertEquals(700.0 / 20.5 * 1_000, lap.sectorTimesMs[1].toDouble(), 30.0)
        }
    }

    @Test
    fun lapWithAMissedSectorGateHasNoSectorTimes() {
        val farAway = LapGates(TestWorld.loopGate, listOf(Gate.fromPointAndHeading(TestWorld.position(900.0, 900.0), 0.0)))
        val laps = detectLaps(loopAtSpeed, farAway)
        assertEquals(3, laps.size)
        assertTrue(laps.all { it.sectorTimesMs.isEmpty() && it.isValid })
    }

    @Test
    fun fixesThatJitterBackAcrossTheGateDoNotMakeALap() {
        val jitter = listOf(
            fix(t0, 80.0, 0.0),
            fix(t0 + 1_000, 110.0, 0.0),
            fix(t0 + 2_000, 90.0, 0.0),
            fix(t0 + 3_000, 110.0, 0.0),
            fix(t0 + 4_000, 130.0, 0.0),
        )
        assertTrue(detectLaps(jitter, gates).isEmpty())
        val withoutDebounce = LapDetectorConfig(minLapFloorMs = 0L, rearmDistanceM = 0.0)
        assertEquals(1, detectLaps(jitter, gates, withoutDebounce).size)
    }

    @Test
    fun crossingBeforeTheMinimumLapTimeIsIgnoredEvenWhenRearmed() {
        val fastTwice = listOf(
            fix(t0, 80.0, 0.0),
            fix(t0 + 1_000, 120.0, 0.0),
            fix(t0 + 2_000, 200.0, 0.0),
            fix(t0 + 3_000, 90.0, 0.0),
            fix(t0 + 4_000, 120.0, 0.0),
        )
        assertTrue(detectLaps(fastTwice, gates).isEmpty())
        assertEquals(1, detectLaps(fastTwice, gates, LapDetectorConfig(minLapFloorMs = 1_000L)).size)
    }

    @Test
    fun gateStaysDisarmedUntilTheCarHasLeftTheGateArea() {
        val parkedNearTheGate = buildList {
            add(fix(t0, 80.0, 0.0))
            add(fix(t0 + 1_000, 120.0, 0.0))
            for (second in 2..14) add(fix(t0 + second * 1_000L, 120.0, 0.0))
            add(fix(t0 + 15_000, 90.0, 0.0))
            add(fix(t0 + 16_000, 110.0, 0.0))
        }
        assertTrue(detectLaps(parkedNearTheGate, gates).isEmpty())
        assertEquals(1, detectLaps(parkedNearTheGate, gates, LapDetectorConfig(rearmDistanceM = 10.0)).size)
    }

    @Test
    fun lapShorterThanHalfTheBestLapIsIgnoredAndTheNextCrossingCountsDouble() {
        val fixes = TestLoop.drive(3_300.0) { distance -> if (distance < 1_000.0) 20.5 else 60.0 }
        val laps = detectLaps(fixes, gates)

        assertEquals(listOf(1, 2), laps.map { it.number })
        assertEquals(2_000.0 / 60 * 1_000, laps[1].lapTimeMs.toDouble(), 300.0)
    }

    @Test
    fun gpsDropoutInsideALapInvalidatesItAndKeepsItOutOfTheBest() {
        val withHole = loopAtSpeed.filterNot { it.timeMs in (t0 + 20_000)..(t0 + 26_000) }
        val detector = LapDetector(gates)
        val laps = withHole.mapNotNull(detector::add)

        assertEquals(3, laps.size)
        assertFalse(laps[0].isValid)
        assertTrue(laps[1].isValid)
        assertTrue(detector.state.bestLap?.number in 2..3)
    }

    @Test
    fun dropoutAcrossTheLineMergesTwoLapsIntoOneInvalidLap() {
        // Lap 2 would start at 53.66 s; the GPS is silent from 52 to 56 s.
        val withHole = loopAtSpeed.filterNot { it.timeMs in (t0 + 52_000)..(t0 + 56_000) }
        val laps = detectLaps(withHole, gates)

        assertEquals(2, laps.size)
        assertFalse(laps[0].isValid)
        assertEquals(2 * 48_780.0, laps[0].lapTimeMs.toDouble(), 40.0)
        assertTrue(laps[1].isValid)
        assertEquals(48_780.0, laps[1].lapTimeMs.toDouble(), 20.0)
    }

    @Test
    fun aJumpToAFarFixAddsNoLapDistanceAndInvalidatesTheLap() {
        val jumped = loopAtSpeed.map { if (it.timeMs == t0 + 30_000) fix(it.timeMs, 5_000.0, 5_000.0, accuracyM = 5.0) else it }
        val laps = detectLaps(jumped, gates)

        assertEquals(3, laps.size)
        assertFalse(laps[0].isValid)
        assertTrue("only the two segments around the jump are lost: ${laps[0].distanceM}", laps[0].distanceM in 940.0..1_000.0)
        assertEquals(48_780.0, laps[0].lapTimeMs.toDouble(), 20.0)
        assertTrue(laps[1].isValid && laps[2].isValid)
    }

    @Test
    fun aSourceWithAnEarlierClockKeepsTimingAfterTheSwitch() {
        val switched = loopAtSpeed.mapIndexed { index, it -> if (index >= 70) it.copy(timeMs = it.timeMs - 3_600_000L) else it }
        val laps = detectLaps(switched, gates)

        // Lap 2 (switch at 70 s) cannot be timed across two clocks and is dropped; the next crossing starts lap 2.
        assertEquals(listOf(1, 2), laps.map { it.number })
        assertTrue(laps.all { it.isValid })
        assertEquals(48_780.0, laps[1].lapTimeMs.toDouble(), 20.0)
    }

    @Test
    fun aNewSegmentDropsTheLapInProgress() {
        val detector = LapDetector(gates)
        loopAtSpeed.take(20).forEach(detector::add)
        detector.startNewSegment()
        assertFalse(detector.state.isTiming)
        val laps = loopAtSpeed.drop(20).mapNotNull(detector::add)

        assertEquals(2, laps.size)
        assertEquals(t0 + 53_658.0, laps[0].startMs.toDouble(), 40.0)
        assertTrue(laps.all { it.isValid })
    }

    @Test
    fun addingASectorGateKeepsTheLapTimerRunning() {
        val detector = LapDetector(gates)
        // 13 fixes reach 246 m: lap 1 has started at 100 m, the sector gate (400 m) is still ahead.
        loopAtSpeed.take(13).forEach(detector::add)
        val elapsedBefore = detector.state.currentLapElapsedMs
        detector.updateSectors(listOf(TestWorld.loopSectorGate))

        assertEquals(elapsedBefore, detector.state.currentLapElapsedMs)
        val laps = loopAtSpeed.drop(13).mapNotNull(detector::add)
        assertEquals("the same laps as with the sector gate from the start", detectLaps(loopAtSpeed, sectorGates), laps)
        assertEquals(2, laps[0].sectorTimesMs.size)
    }

    @Test
    fun inaccurateFixesAreIgnored() {
        val garbage = fix(t0 + 30_500, 5_000.0, 5_000.0, accuracyM = 50.0)
        val withGarbage = (loopAtSpeed + garbage).sortedBy { it.timeMs }
        val laps = detectLaps(withGarbage, gates)

        assertEquals(3, laps.size)
        assertTrue(laps.all { it.isValid })
        assertEquals(48_780.0, laps[0].lapTimeMs.toDouble(), 20.0)
    }

    @Test
    fun fixesNotNewerThanThePreviousAreIgnored() {
        val detector = LapDetector(gates)
        val duplicated = loopAtSpeed.flatMap { listOf(it, it) }
        assertEquals(3, duplicated.mapNotNull(detector::add).size)
        assertNull(detector.add(loopAtSpeed.first()))
    }

    @Test
    fun liveStateTracksTheCurrentLapAndTheDeltaToTheBestLap() {
        val fixes = TestLoop.drive(1_700.0) { distance -> if (distance < 1_100.0) 20.0 else 25.0 }
        val detector = LapDetector(gates)
        assertFalse(detector.state.isTiming)
        assertNull(detector.state.currentLapElapsedMs)

        fixes.forEach(detector::add)
        val state = detector.state

        assertTrue(state.isTiming)
        assertEquals(1, state.lapCount)
        assertEquals(1, state.lastLap?.number)
        assertEquals(state.lastLap, state.bestLap)
        assertEquals(50_000.0, state.bestLap!!.lapTimeMs.toDouble(), 20.0)
        val intoLapMs = fixes.last().timeMs - state.lastLap!!.endMs
        assertEquals(intoLapMs.toDouble(), state.currentLapElapsedMs!!.toDouble(), 0.0)
        assertEquals(-6_000.0, state.deltaToBestMs!!.toDouble(), 500.0)
        assertEquals(state.bestLap!!.lapTimeMs + state.deltaToBestMs!!, state.predictedLapMs)
    }

    @Test
    fun sectorSplitsAreShownWhileTheLapIsRunning() {
        val detector = LapDetector(sectorGates)
        TestLoop.drive(1_000.0, 20.5).forEach(detector::add)
        val state = detector.state
        assertEquals(1, state.currentSplitsMs.size)
        assertEquals(300.0 / 20.5 * 1_000, state.currentSplitsMs[0].toDouble(), 30.0)
        assertNotNull(state.currentLapElapsedMs)
    }

    @Test
    fun noBestLapMeansNoDelta() {
        val detector = LapDetector(gates)
        TestLoop.drive(600.0, 20.5).forEach(detector::add)
        assertTrue(detector.state.isTiming)
        assertNull(detector.state.deltaToBestMs)
        assertNull(detector.state.predictedLapMs)
        assertEquals(0, detector.state.lapCount)
    }

    @Test
    fun theFixesOfTheLapInProgressAreKeptFromTheFixBeforeTheStartCrossing() {
        val detector = LapDetector(gates)
        assertTrue(detector.currentLapFixes().isEmpty())
        // 13 fixes reach 246 m: lap 1 started at 100 m, between the fixes at 82 m and 102.5 m.
        loopAtSpeed.take(13).forEach(detector::add)
        val trail = detector.currentLapFixes()

        assertEquals(loopAtSpeed[4], trail.first())
        assertEquals(loopAtSpeed[12], trail.last())
        assertEquals(9, trail.size)
    }

    @Test
    fun theLatestValidCompleteLapIsTheReferenceLap() {
        val detector = LapDetector(gates)
        assertTrue(detector.state.referenceLap.isEmpty())
        val laps = loopAtSpeed.mapNotNull(detector::add)
        assertEquals(3, laps.size)

        val reference = detector.state.referenceLap
        assertEquals(TestLoop.LENGTH_M, SectorOrder.lapLengthM(reference), 50.0)
        assertTrue("it starts just before the start crossing", reference.first().timeMs < laps.last().startMs)
        assertTrue("and ends just after the end crossing", reference.last().timeMs >= laps.last().endMs)
    }

    @Test
    fun aLapWithADropoutIsNoReferenceLap() {
        val detector = LapDetector(gates)
        val withDropout = loopAtSpeed.filterNot { it.timeMs in (t0 + 20_000)..(t0 + 30_000) }
        withDropout.takeWhile { it.timeMs < t0 + 4_878 + 48_780 + 2_000 }.forEach(detector::add)

        assertEquals(1, detector.state.lapCount)
        assertFalse(checkNotNull(detector.state.lastLap).isValid)
        assertTrue(detector.state.referenceLap.isEmpty())
    }

    @Test
    fun aLapThatNeverEndsKeepsABoundedTrace() {
        val detector = LapDetector(gates)
        TestLoop.drive(150.0, 20.5).forEach(detector::add)
        val straight = (1..20_000).map { TestWorld.fix(t0 + 7_000L + it * 1_000L, 143.5 + it * 20.0, 0.0) }
        straight.forEach(detector::add)

        val trail = detector.currentLapFixes()
        assertTrue("${trail.size} fixes", trail.size in 1..8_000)
        assertEquals(straight.last(), trail.last())
    }
}
