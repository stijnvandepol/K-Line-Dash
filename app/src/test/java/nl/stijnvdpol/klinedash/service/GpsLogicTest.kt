package nl.stijnvdpol.klinedash.service

import kotlin.math.hypot
import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.geo.Geodesy
import nl.stijnvdpol.klinedash.core.geo.LapGates
import nl.stijnvdpol.klinedash.core.geo.LatLon
import nl.stijnvdpol.klinedash.core.geo.LocalFrame
import nl.stijnvdpol.klinedash.core.geo.detectLaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsStatusTest {
    private val fix = GeoFix(1_000, 51.0, 5.0, 10.0, 90.0, accuracyM = 4.0, satellites = 9)

    @Test
    fun statusFollowsTheConditionsInOrder() {
        assertEquals(GpsStatus.NoPermission, gpsStatusOf(false, false, false, fix, 1.0))
        assertEquals(GpsStatus.Disabled, gpsStatusOf(true, false, true, fix, 1.0))
        assertEquals(GpsStatus.Idle, gpsStatusOf(true, true, false, fix, 1.0))
        assertEquals(GpsStatus.Searching, gpsStatusOf(true, true, true, null, null))
        assertEquals(GpsStatus.Fix(4.0, 9, 5.0), gpsStatusOf(true, true, true, fix, 5.0))
    }

    @Test
    fun rateIsTheMedianIntervalAndIgnoresOneLateFix() {
        val meter = FixRateMeter()
        assertNull(meter.rateHz)
        var t = 0L
        repeat(6) { meter.add(t); t += 200 }
        t += 800 // one late fix
        meter.add(t)
        assertEquals(5.0, checkNotNull(meter.rateHz), 0.01)
    }

    @Test
    fun aGapIsNotAnInterval() {
        val meter = FixRateMeter()
        meter.add(0)
        meter.add(1_000)
        meter.add(2_000)
        meter.add(3_000)
        meter.add(60_000) // tunnel
        assertEquals(3, meter.sampleCount)
        assertEquals(1.0, checkNotNull(meter.rateHz), 0.01)
    }

    @Test
    fun aFixIsFreshOnlyForAShortTimeOnTheMonotonicClock() {
        var now = 10_000L
        val store = FixStore { now }
        assertNull(store.fresh(3_000))
        store.onFix(fix, now)
        now += 2_900
        assertEquals(fix, store.fresh(3_000))
        now += 200
        assertNull("3.1 s old", store.fresh(3_000))
        assertEquals("the stale fix is still the latest one", fix, store.fix.value)
        store.clear()
        assertNull(store.fix.value)
    }

    @Test
    fun sampleIntervalFollowsTheFixRateWithinOneToFiveHertz() {
        assertEquals(1_000L, sampleIntervalMs(null))
        assertEquals(1_000L, sampleIntervalMs(1.0))
        assertEquals(500L, sampleIntervalMs(2.0))
        assertEquals(200L, sampleIntervalMs(5.0))
        assertEquals("never faster than 5 Hz", 200L, sampleIntervalMs(10.0))
    }

    @Test
    fun locationStartsWhenVisibleAndStopsAfterTheGraceWhenHiddenAndIdle() {
        assertEquals(GpsAction.START, decideGpsAction(isAppVisible = true, canStart = true, isRunning = false, isRecording = false))
        assertEquals("no permission, nothing to start", GpsAction.NONE, decideGpsAction(true, false, false, false))
        assertEquals("not started while hidden (boot)", GpsAction.NONE, decideGpsAction(false, true, false, false))
        assertEquals(GpsAction.STOP_AFTER_GRACE, decideGpsAction(false, true, true, false))
        assertEquals("a recording keeps it on", GpsAction.NONE, decideGpsAction(false, true, true, true))
        assertEquals("permission taken away", GpsAction.STOP, decideGpsAction(true, false, true, true))
    }
}

class MotionDetectorTest {
    @Test
    fun startsAfterFiveSecondsAboveTenKmh() {
        val detector = MotionDetector()
        assertEquals(false, detector.update(0, 12.0))
        assertEquals(false, detector.update(4_900, 15.0))
        assertEquals(true, detector.update(5_000, 15.0))
    }

    @Test
    fun aSlowSampleRestartsTheFiveSeconds() {
        val detector = MotionDetector()
        detector.update(0, 20.0)
        detector.update(3_000, 8.0)
        assertEquals(false, detector.update(6_000, 20.0))
        assertEquals(true, detector.update(11_000, 20.0))
    }

    @Test
    fun tenKmhExactlyIsNotAboveTenKmh() {
        val detector = MotionDetector()
        detector.update(0, 10.0)
        assertEquals(false, detector.update(60_000, 10.0))
    }

    @Test
    fun keepsMovingThroughSlowCornersButNotWhenStopped() {
        val detector = MotionDetector()
        detector.update(0, 30.0)
        assertTrue(detector.update(5_000, 30.0))
        assertTrue("a slow corner", detector.update(6_000, 4.0))
        assertEquals("stopped", false, detector.update(7_000, 1.0))
        assertEquals("and needs the full five seconds again", false, detector.update(8_000, 30.0))
    }

    @Test
    fun anUnknownSpeedMeansNotMoving() {
        val detector = MotionDetector()
        detector.update(0, 30.0)
        detector.update(5_000, 30.0)
        assertEquals(false, detector.update(6_000, null))
    }
}

class GatePlacementTest {
    private val good = GeoFix(1_000, 51.0, 5.0, speedMps = 20.0, bearingDeg = 370.0, accuracyM = 5.0)

    private fun reason(fix: GeoFix?) = (placeGateAt(fix) as GatePlacement.Rejected).reason

    @Test
    fun aRecentAccurateMovingFixGivesAGateAcrossTheDrivingDirection() {
        val spec = (placeGateAt(good, widthM = 40.0) as GatePlacement.Ready).spec
        assertEquals(10.0, spec.headingDeg, 1e-9)
        assertEquals(40.0, spec.widthM, 0.0)
        val gate = spec.toGate()
        val ahead = Geodesy.destination(LatLon(51.0, 5.0), 10.0, 20.0)
        val behind = Geodesy.destination(LatLon(51.0, 5.0), 10.0, -20.0)
        val crossing = gate.crossing(good.copy(timeMs = 0, lat = behind.lat, lon = behind.lon), good.copy(timeMs = 2_000, lat = ahead.lat, lon = ahead.lon))
        assertEquals("the car's own direction crosses the line", 1_000L, checkNotNull(crossing).timeMs)
    }

    @Test
    fun noFixIsRefused() {
        assertTrue(reason(null).contains("no recent GPS fix"))
    }

    @Test
    fun aPoorOrUnknownAccuracyIsRefused() {
        assertTrue(reason(good.copy(accuracyM = 40.0)).contains("not accurate enough"))
        assertTrue(reason(good.copy(accuracyM = null)).contains("unknown"))
        assertTrue(placeGateAt(good.copy(accuracyM = GATE_MAX_ACCURACY_M)) is GatePlacement.Ready)
    }

    @Test
    fun withoutSpeedOrHeadingTheDirectionIsUnknown() {
        assertTrue(reason(good.copy(speedMps = 0.5)).contains("direction"))
        assertTrue(reason(good.copy(speedMps = null)).contains("direction"))
        assertTrue(reason(good.copy(bearingDeg = null)).contains("direction"))
    }
}

class RouteDecimationTest {
    private fun circle(count: Int, radiusM: Double = 300.0): List<LatLon> {
        val center = LatLon(51.0, 5.0)
        return (0 until count).map { Geodesy.destination(center, 360.0 * it / count, radiusM) }
    }

    @Test
    fun aShortRouteComesBackUnchanged() {
        val route = circle(100)
        assertEquals(route, decimateRoute(route, maxPoints = 100))
    }

    /** A 20 km wiggly road: every point matters, so the whole budget is used. */
    private fun wiggles(count: Int): List<LatLon> {
        val origin = LatLon(51.0, 5.0)
        return (0 until count).map { index ->
            val along = Geodesy.destination(origin, 90.0, index.toDouble())
            Geodesy.destination(along, 0.0, 50.0 * kotlin.math.sin(index * 2 * Math.PI / 200.0))
        }
    }

    @Test
    fun aLongRouteIsReducedToTheBudgetKeepingEndsAndOrder() {
        val route = wiggles(20_000)
        val reduced = decimateRoute(route, maxPoints = MAX_ROUTE_POINTS)
        assertEquals(MAX_ROUTE_POINTS, reduced.size)
        assertEquals(route.first(), reduced.first())
        assertEquals(route.last(), reduced.last())
        val indexes = reduced.map { route.indexOf(it) }
        assertEquals("points keep their driving order", indexes.sorted(), indexes)
    }

    @Test
    fun aSmoothRouteStopsEarlyBecauseMorePointsAddNothingVisible() {
        val reduced = decimateRoute(circle(20_000), maxPoints = MAX_ROUTE_POINTS)
        assertTrue("${reduced.size} points", reduced.size in 20..MAX_ROUTE_POINTS / 4)
    }

    @Test
    fun theShapeSurvivesWithinAMetre() {
        val route = circle(20_000)
        val reduced = decimateRoute(route, maxPoints = 500)
        val frame = LocalFrame(route.first())
        val line = reduced.map(frame::project)
        val worst = route.map(frame::project).maxOf { p ->
            line.zipWithNext().minOf { (a, b) -> distanceToSegment(p.x, p.y, a.x, a.y, b.x, b.y) }
        }
        assertTrue("worst deviation $worst m", worst < 1.0)
    }

    @Test
    fun aStraightLineCollapsesToItsEndPoints() {
        val line = (0..5_000).map { LatLon(51.0 + it * 1e-6, 5.0) }
        assertEquals(listOf(line.first(), line.last()), decimateRoute(line, maxPoints = 2_000))
    }

    @Test
    fun theRouteToDrawLeavesOutAStrayFix() {
        val fixes = (0 until 60).map { GeoFix(TEST_BASE_MS + it * 1_000L, 51.0, 5.0 + it * 0.0002, accuracyM = 3.0) }
        val stray = GeoFix(TEST_BASE_MS + 30_500L, 52.0, 6.0, accuracyM = 3.0)

        val route = routeOf((fixes.take(31) + stray + fixes.drop(31)))

        assertEquals(fixes.first().position, route.first())
        assertEquals(fixes.last().position, route.last())
        assertTrue("no point near the stray fix", route.all { it.lat < 51.01 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBudgetBelowTwoIsRejected() {
        decimateRoute(circle(10), maxPoints = 1)
    }

    private fun distanceToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}

class SimulatedCircuitTest {
    @Test
    fun theDefaultCircuitIsTwoKilometres() {
        assertEquals(2_000.0, testCircuit.lengthM, 1e-6)
    }

    @Test
    fun theCircuitIsClosedAndSmooth() {
        val start = testCircuit.pointAt(0.0).first
        assertEquals(0.0, Geodesy.distanceM(start, testCircuit.pointAt(testCircuit.lengthM).first), 0.01)
        var previous = start
        for (step in 1..2_000) {
            val point = testCircuit.pointAt(step.toDouble()).first
            assertEquals("one metre along the road is one metre on the map", 1.0, Geodesy.distanceM(previous, point), 0.02)
            previous = point
        }
    }

    @Test
    fun theHeadingIsTheDirectionOfTravel() {
        for (distance in listOf(100.0, 600.0, 900.0, 1_200.0, 1_600.0, 1_900.0)) {
            val (here, heading) = testCircuit.pointAt(distance)
            val ahead = testCircuit.pointAt(distance + 1.0).first
            val travel = Geodesy.bearingDeg(here, ahead)
            val difference = Math.abs(((travel - heading + 540.0) % 360.0) - 180.0)
            assertTrue("at $distance m: heading $heading, travel $travel", difference < 1.0)
        }
        assertEquals(0.0, testCircuit.pointAt(0.0).second, 1e-9)
    }

    @Test
    fun lapsAreTimedAtTheSimulatedSpeed() {
        val fixes = circuitFixes(speedKmh = 72.0, durationMs = 250_000)
        val laps = detectLaps(fixes, LapGates(circuitTrack().lapGates().startFinish))
        assertEquals(2, laps.size)
        laps.forEach { assertEquals("2000 m at 20 m/s", 100_000.0, it.lapTimeMs.toDouble(), 100.0) }
    }
}
