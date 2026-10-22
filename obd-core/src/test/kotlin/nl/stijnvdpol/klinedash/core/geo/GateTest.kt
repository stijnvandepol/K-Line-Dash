package nl.stijnvdpol.klinedash.core.geo

import nl.stijnvdpol.klinedash.core.assertFails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GateTest {

    /** Heading north: valid crossings go from south to north. */
    private val gate = Gate.fromPointAndHeading(TestWorld.position(0.0, 0.0), 0.0)
    private val t0 = TestWorld.T0

    private fun fix(timeMs: Long, east: Double, north: Double) = TestWorld.fix(timeMs, east, north)

    @Test
    fun gateFromPointAndHeadingIsThirtyMetresWideAndCentredOnThePoint() {
        assertEquals(30.0, Geodesy.distanceM(gate.a, gate.b), 0.01)
        assertEquals(0.0, Geodesy.distanceM(gate.midpoint, TestWorld.position(0.0, 0.0)), 0.01)
        val wide = Gate.fromPointAndHeading(TestWorld.position(0.0, 0.0), 45.0, 50.0)
        assertEquals(50.0, Geodesy.distanceM(wide.a, wide.b), 0.01)
    }

    @Test
    fun gateWidthMustBePositive() {
        assertFails<IllegalArgumentException> { Gate.fromPointAndHeading(TestWorld.position(0.0, 0.0), 0.0, 0.0) }
    }

    @Test
    fun crossingInTheSetDirectionInterpolatesTheTime() {
        val crossing = gate.crossing(fix(t0, 0.0, -30.0), fix(t0 + 1_000, 0.0, 10.0))
        assertNotNull(crossing)
        assertEquals(0.75, crossing!!.fraction, 1e-3)
        assertEquals(t0 + 750, crossing.timeMs)
    }

    @Test
    fun crossingAgainstTheSetDirectionIsRejected() {
        assertNull(gate.crossing(fix(t0, 0.0, 10.0), fix(t0 + 1_000, 0.0, -30.0)))
    }

    @Test
    fun gateSetFacingEastOnlyCountsEastboundCrossings() {
        val east = Gate.fromPointAndHeading(TestWorld.position(0.0, 0.0), 90.0)
        assertNotNull(east.crossing(fix(t0, -10.0, 0.0), fix(t0 + 1_000, 10.0, 0.0)))
        assertNull(east.crossing(fix(t0, 10.0, 0.0), fix(t0 + 1_000, -10.0, 0.0)))
        assertNull(east.crossing(fix(t0, 0.0, -10.0), fix(t0 + 1_000, 0.0, 10.0)))
    }

    @Test
    fun crossingBesideTheGateIsIgnored() {
        assertNull(gate.crossing(fix(t0, 20.0, -10.0), fix(t0 + 1_000, 20.0, 10.0)))
    }

    @Test
    fun segmentThatStopsShortOfTheGateIsIgnored() {
        assertNull(gate.crossing(fix(t0, 0.0, -30.0), fix(t0 + 1_000, 0.0, -5.0)))
    }

    @Test
    fun segmentParallelToTheGateIsIgnored() {
        assertNull(gate.crossing(fix(t0, -10.0, 0.0), fix(t0 + 1_000, 10.0, 0.0)))
    }

    @Test
    fun diagonalCrossingIsInterpolatedAlongTheSegment() {
        val crossing = gate.crossing(fix(t0, -20.0, -10.0), fix(t0 + 2_000, 20.0, 10.0))
        assertEquals(t0 + 1_000, crossing!!.timeMs)
    }

    @Test
    fun fixOnTheGateCountsForExactlyOneOfTheTwoSegmentsSharingIt() {
        val before = fix(t0, 0.0, -20.0)
        val onGate = fix(t0 + 1_000, 0.0, 0.0)
        val after = fix(t0 + 2_000, 0.0, 20.0)
        val crossings = listOfNotNull(gate.crossing(before, onGate), gate.crossing(onGate, after))
        assertEquals(1, crossings.size)
        assertEquals(t0 + 1_000.0, crossings.single().timeMs.toDouble(), 1.0)
    }

    @Test
    fun fixesNotInTimeOrderNeverCross() {
        assertNull(gate.crossing(fix(t0 + 1_000, 0.0, -10.0), fix(t0, 0.0, 10.0)))
        assertNull(gate.crossing(fix(t0, 0.0, -10.0), fix(t0, 0.0, 10.0)))
    }
}
