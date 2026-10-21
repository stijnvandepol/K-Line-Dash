package nl.stijnvdpol.klinedash.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RpmBarTest {

    @Test
    fun `the bar covers 9000 rpm in 45 segments of 200`() {
        assertEquals(9_000, RPM_BAR_SEGMENTS * SEGMENT_RPM)
    }

    @Test
    fun `segments are white below 6200, caution up to 7800 and danger from there`() {
        assertEquals(RpmZone.NORMAL, rpmZone(0))
        assertEquals(RpmZone.NORMAL, rpmZone(6_000))
        assertEquals(RpmZone.CAUTION, rpmZone(6_200))
        assertEquals(RpmZone.CAUTION, rpmZone(7_600))
        assertEquals(RpmZone.DANGER, rpmZone(7_800))
        assertEquals(RpmZone.DANGER, rpmZone(8_800))
    }

    @Test
    fun `a segment lights as soon as the rpm passes its start`() {
        assertEquals(0, litSegmentCount(0.0))
        assertEquals(1, litSegmentCount(1.0))
        assertEquals(1, litSegmentCount(200.0))
        assertEquals(2, litSegmentCount(201.0))
        assertEquals(5, litSegmentCount(830.0))
    }

    @Test
    fun `lit segments stick to the ends of the bar`() {
        assertEquals(0, litSegmentCount(-50.0))
        assertEquals(45, litSegmentCount(9_000.0))
        assertEquals(45, litSegmentCount(12_000.0))
    }

    @Test
    fun `bar takes as long as the measured interval between readings`() {
        assertEquals(400, sweepDurationMs(2.5))
        assertEquals(1000, sweepDurationMs(1.0))
    }

    @Test
    fun `sweep duration is clamped between 100 and 1000 ms`() {
        assertEquals(100, sweepDurationMs(50.0))
        assertEquals(1000, sweepDurationMs(0.2))
    }

    @Test
    fun `sweep duration falls back to a default before anything is measured`() {
        assertEquals(500, sweepDurationMs(null))
        assertEquals(500, sweepDurationMs(0.0))
    }
}
