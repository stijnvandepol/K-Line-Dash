package nl.stijnvdpol.klinedash.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LapIndexAndProfileTest {

    private fun lap(number: Int, startMs: Long, endMs: Long) = Lap(number, startMs, endMs, emptyList(), 1_000.0, true)

    private val index = LapIndex(listOf(lap(1, 1_000, 2_000), lap(2, 2_000, 3_500), lap(3, 3_500, 4_000)))

    @Test
    fun lapIndexFindsTheLapContainingATime() {
        assertEquals(1, index.numberAt(1_000))
        assertEquals(1, index.numberAt(1_999))
        assertEquals(2, index.numberAt(2_000))
        assertEquals(3, index.numberAt(3_999))
    }

    @Test
    fun lapIndexReturnsNullOutsideAllLaps() {
        assertNull(index.numberAt(999))
        assertNull(index.numberAt(4_000))
        assertNull(LapIndex(emptyList()).numberAt(1_500))
    }

    private fun profile(): LapProfile {
        val builder = LapProfileBuilder()
        builder.addPoint(0.0, 0)
        builder.addPoint(100.0, 5_000)
        builder.addPoint(300.0, 15_000)
        return builder.build()
    }

    @Test
    fun profileInterpolatesBetweenPoints() {
        val profile = profile()
        assertEquals(0L, profile.elapsedAtDistance(0.0))
        assertEquals(2_500L, profile.elapsedAtDistance(50.0))
        assertEquals(5_000L, profile.elapsedAtDistance(100.0))
        assertEquals(10_000L, profile.elapsedAtDistance(200.0))
        assertEquals(15_000L, profile.elapsedAtDistance(300.0))
    }

    @Test
    fun profileHasNoAnswerBeyondItsEndOrBeforeItsStart() {
        val profile = profile()
        assertNull(profile.elapsedAtDistance(300.1))
        assertNull(profile.elapsedAtDistance(-1.0))
        assertNull(LapProfile(DoubleArray(0), LongArray(0)).elapsedAtDistance(0.0))
    }

    @Test
    fun profileCopesWithStandstillWhereDistanceDoesNotGrow() {
        val builder = LapProfileBuilder()
        builder.addPoint(0.0, 0)
        builder.addPoint(100.0, 5_000)
        builder.addPoint(100.0, 9_000)
        builder.addPoint(200.0, 12_000)
        assertEquals(5_000L, builder.build().elapsedAtDistance(100.0))
        assertEquals(10_500L, builder.build().elapsedAtDistance(150.0))
    }
}
