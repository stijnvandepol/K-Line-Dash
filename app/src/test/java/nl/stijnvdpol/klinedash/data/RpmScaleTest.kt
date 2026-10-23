package nl.stijnvdpol.klinedash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RpmScaleTest {
    @Test
    fun `the scale ends at a whole thousand at least a thousand above the redline`() {
        assertEquals(6_000, rpmScale(4_500).maxRpm)
        assertEquals(8_000, rpmScale(6_500).maxRpm)
        assertEquals(9_000, rpmScale(8_000).maxRpm)
        assertEquals(11_000, rpmScale(10_000).maxRpm)
    }

    @Test
    fun `the caution zone starts a thousand below the redline`() {
        assertEquals(RpmScale(maxRpm = 6_000, cautionRpm = 3_500, redlineRpm = 4_500), rpmScale(4_500))
        assertEquals(RpmScale(maxRpm = 8_000, cautionRpm = 5_500, redlineRpm = 6_500), rpmScale(6_500))
        assertEquals(RpmScale(maxRpm = 9_000, cautionRpm = 7_000, redlineRpm = 8_000), rpmScale(8_000))
        assertEquals(RpmScale(maxRpm = 11_000, cautionRpm = 9_000, redlineRpm = 10_000), rpmScale(10_000))
    }

    @Test
    fun `a redline outside the allowed range is clamped`() {
        assertEquals(rpmScale(MIN_REDLINE_RPM), rpmScale(500))
        assertEquals(rpmScale(MAX_REDLINE_RPM), rpmScale(50_000))
    }

    @Test
    fun `the highest shift point is the end of the scale of the highest redline`() {
        assertEquals(rpmScale(MAX_REDLINE_RPM).maxRpm, MAX_SHIFT_RPM)
    }

    @Test
    fun `the lowest shift point fits on the scale of the lowest redline`() {
        assertTrue(MIN_SHIFT_RPM <= rpmScale(MIN_REDLINE_RPM).maxRpm)
    }

    @Test
    fun `the shift point never lies past the end of the scale`() {
        assertEquals(6_200, rpmScale(6_500).shiftPoint(6_200))
        assertEquals(8_000, rpmScale(6_500).shiftPoint(8_000))
        assertEquals(8_000, rpmScale(6_500).shiftPoint(9_000))
        assertEquals(6_000, rpmScale(4_500).shiftPoint(7_800))
        assertEquals(7_800, rpmScale(8_000).shiftPoint(7_800))
        assertEquals(10_500, rpmScale(10_000).shiftPoint(10_500))
    }

    @Test
    fun `a new install flashes the shift light before the red zone of the default redline`() {
        assertTrue(DEFAULT_SHIFT_RPM < DEFAULT_REDLINE_RPM)
        assertEquals(DEFAULT_SHIFT_RPM, rpmScale(DEFAULT_REDLINE_RPM).shiftPoint(DEFAULT_SHIFT_RPM))
    }
}
