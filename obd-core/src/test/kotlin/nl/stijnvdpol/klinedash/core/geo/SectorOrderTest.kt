package nl.stijnvdpol.klinedash.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SectorOrderTest {

    /** One lap of the test loop at 20 m/s, 1 Hz: the south edge (east), the east edge (north), the north edge (west), the west edge (south). */
    private val lap = TestLoop.drive(TestLoop.LENGTH_M, 20.0)

    private val southEdge = Gate.fromPointAndHeading(TestWorld.position(200.0, 0.0), 90.0)
    private val eastEdge = TestWorld.loopSectorGate
    private val northEdge = Gate.fromPointAndHeading(TestWorld.position(150.0, 200.0), 270.0)
    private val offTheLoop = Gate.fromPointAndHeading(TestWorld.position(900.0, 900.0), 0.0)

    @Test
    fun theLapIsAboutOneLoopLong() {
        assertEquals(TestLoop.LENGTH_M, SectorOrder.lapLengthM(lap), 30.0)
    }

    @Test
    fun aGateIsPositionedByTheDistanceAlongTheLap() {
        assertEquals(200.0, checkNotNull(SectorOrder.positionAlongLapM(southEdge, lap)), 1.0)
        assertEquals(400.0, checkNotNull(SectorOrder.positionAlongLapM(eastEdge, lap)), 1.0)
        assertEquals(650.0, checkNotNull(SectorOrder.positionAlongLapM(northEdge, lap)), 1.0)
    }

    @Test
    fun aGateTheLapNeverCrossesHasNoPosition() {
        assertNull(SectorOrder.positionAlongLapM(offTheLoop, lap))
        val wrongWay = Gate(southEdge.b, southEdge.a)
        assertNull("a gate only counts in its own direction", SectorOrder.positionAlongLapM(wrongWay, lap))
    }

    @Test
    fun aLapThatHasNotReachedAGateYetDoesNotCrossIt() {
        val partial = lap.takeWhile { it.timeMs < TestWorld.T0 + 15_000 }
        assertNotNull(SectorOrder.positionAlongLapM(southEdge, partial))
        assertNull(SectorOrder.positionAlongLapM(eastEdge, partial))
    }

    @Test
    fun aJumpInTheTraceAddsNoDistance() {
        val jump = TestWorld.fix(TestWorld.T0 + 5_000, 40_000.0, 40_000.0)
        val withJump = lap.take(5) + jump + lap.drop(5)
        assertEquals(TestLoop.LENGTH_M, SectorOrder.lapLengthM(withJump), 150.0)
    }

    @Test
    fun sectorsListedOutOfDrivingOrderComeBackInDrivingOrder() {
        val ordered = SectorOrder.orderAlongLap(listOf(northEdge, eastEdge, southEdge), lap) { it }
        assertEquals(listOf(southEdge, eastEdge, northEdge), ordered)
    }

    @Test
    fun anOrderedListIsLeftAlone() {
        val sectors = listOf(southEdge, eastEdge, northEdge)
        assertEquals(sectors, SectorOrder.orderAlongLap(sectors, lap) { it })
    }

    @Test
    fun aListWithAGateTheLapNeverCrossesIsLeftAlone() {
        val sectors = listOf(northEdge, offTheLoop, southEdge)
        assertEquals(sectors, SectorOrder.orderAlongLap(sectors, lap) { it })
    }

    @Test
    fun gatesAheadOfALapInProgressGoLastInTheirOwnOrder() {
        val partial = lap.takeWhile { it.timeMs < TestWorld.T0 + 15_000 }
        val ordered = SectorOrder.sortedByPosition(listOf(northEdge, eastEdge, southEdge)) { SectorOrder.positionAlongLapM(it, partial) }
        assertEquals(listOf(southEdge, northEdge, eastEdge), ordered)
    }

    @Test
    fun withoutALapTheListKeepsItsOrder() {
        val sectors = listOf(northEdge, southEdge, eastEdge)
        assertEquals(sectors, SectorOrder.orderAlongLap(sectors, emptyList()) { it })
    }

    @Test
    fun equalPositionsKeepTheirOrder() {
        val items = listOf("b", "a", "c")
        assertEquals(items, SectorOrder.sortedByPosition(items) { 1.0 })
        assertEquals(listOf("a", "b", "c"), SectorOrder.sortedByPosition(items) { (it.single() - 'a').toDouble() })
    }
}
