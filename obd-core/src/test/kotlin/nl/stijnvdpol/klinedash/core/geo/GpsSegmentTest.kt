package nl.stijnvdpol.klinedash.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsSegmentTest {
    private val t0 = TestWorld.T0

    /** A car driving east at 20 m/s, a fix per second, [count] fixes. */
    private fun drive(count: Int, startStep: Int = 0) =
        (startStep until startStep + count).map { TestWorld.fix(t0 + it * 1_000L, it * 20.0, 0.0, accuracyM = 3.0) }

    private fun farFix(step: Int) = TestWorld.fix(t0 + step * 1_000L, 60_000.0, 60_000.0, accuracyM = 3.0)

    @Test
    fun aCleanDriveComesBackUnchanged() {
        val fixes = drive(30)
        assertEquals(fixes, GpsSegment.dropJumps(fixes))
    }

    @Test
    fun oneFarFixInTheMiddleIsDropped() {
        val fixes = drive(10) + farFix(10) + drive(10, startStep = 11)
        assertEquals(drive(10) + drive(10, startStep = 11), GpsSegment.dropJumps(fixes))
    }

    @Test
    fun aFarFirstFixIsDropped() {
        val fixes = listOf(farFix(0)) + drive(10, startStep = 1)
        assertEquals(drive(10, startStep = 1), GpsSegment.dropJumps(fixes))
    }

    @Test
    fun aFarFixAtTheEndIsDropped() {
        val fixes = drive(10) + farFix(10)
        assertEquals(drive(10), GpsSegment.dropJumps(fixes))
    }

    @Test
    fun aPairOfStrayFixesIsDroppedToo() {
        val fixes = drive(10) + farFix(10) + farFix(11) + drive(10, startStep = 12)
        assertEquals(drive(10) + drive(10, startStep = 12), GpsSegment.dropJumps(fixes))
    }

    @Test
    fun inaccurateFixesAndRepeatsAreDropped() {
        val sloppy = TestWorld.fix(t0 + 4_500, 90.0, 500.0, accuracyM = 80.0)
        val fixes = drive(10)
        val repeated = fixes.take(5) + fixes[4] + sloppy + fixes.drop(5)
        assertEquals(fixes, GpsSegment.dropJumps(repeated))
    }

    @Test
    fun aVeryShortTripKeepsItsLongestPiece() {
        val fixes = listOf(farFix(0)) + drive(2, startStep = 1)
        assertEquals(drive(2, startStep = 1), GpsSegment.dropJumps(fixes))
    }

    @Test
    fun nothingGivesNothing() {
        assertTrue(GpsSegment.dropJumps(emptyList()).isEmpty())
    }
}
