package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.data.rpmScale
import org.junit.Assert.assertEquals
import org.junit.Test

class RpmBarTest {
    private val redlines = listOf(4_500, 6_500, 8_000, 10_000)

    @Test
    fun `the bar has a segment of 200 rpm for each 200 rpm of the scale`() {
        assertEquals(30, segmentCount(rpmScale(4_500)))
        assertEquals(40, segmentCount(rpmScale(6_500)))
        assertEquals(45, segmentCount(rpmScale(8_000)))
        assertEquals(55, segmentCount(rpmScale(10_000)))
    }

    @Test
    fun `the segments always cover the scale exactly`() {
        for (redline in redlines) {
            val scale = rpmScale(redline)
            assertEquals(scale.maxRpm, segmentCount(scale) * SEGMENT_RPM)
        }
    }

    @Test
    fun `there is a scale numeral per thousand rpm from 0 to the end of the scale`() {
        assertEquals((0..6).toList(), scaleNumerals(rpmScale(4_500)).toList())
        assertEquals((0..8).toList(), scaleNumerals(rpmScale(6_500)).toList())
        assertEquals((0..9).toList(), scaleNumerals(rpmScale(8_000)).toList())
        assertEquals((0..11).toList(), scaleNumerals(rpmScale(10_000)).toList())
    }

    @Test
    fun `a numeral stands on a segment edge, as the end of the scale is the end of the bar`() {
        for (redline in redlines) {
            val scale = rpmScale(redline)
            assertEquals(0, segmentCount(scale) % (SCALE_STEP_RPM / SEGMENT_RPM))
        }
    }

    @Test
    fun `segments are normal below the caution rpm, caution up to the redline and danger from there`() {
        val scale = rpmScale(6_500)
        assertEquals(RpmZone.NORMAL, rpmZone(0, scale))
        assertEquals(RpmZone.NORMAL, rpmZone(5_400, scale))
        assertEquals(RpmZone.CAUTION, rpmZone(5_500, scale))
        assertEquals(RpmZone.CAUTION, rpmZone(6_400, scale))
        assertEquals(RpmZone.DANGER, rpmZone(6_500, scale))
        assertEquals(RpmZone.DANGER, rpmZone(7_800, scale))
    }

    @Test
    fun `the zones follow the redline of the car`() {
        for (redline in redlines) {
            val scale = rpmScale(redline)
            assertEquals(RpmZone.NORMAL, rpmZone(scale.cautionRpm - 1, scale))
            assertEquals(RpmZone.CAUTION, rpmZone(scale.cautionRpm, scale))
            assertEquals(RpmZone.CAUTION, rpmZone(redline - 1, scale))
            assertEquals(RpmZone.DANGER, rpmZone(redline, scale))
            assertEquals(RpmZone.DANGER, rpmZone(scale.maxRpm - SEGMENT_RPM, scale))
        }
    }

    @Test
    fun `the last segments of every scale are in the danger zone`() {
        for (redline in redlines) {
            val scale = rpmScale(redline)
            val zones = (0 until segmentCount(scale)).map { rpmZone(it * SEGMENT_RPM, scale) }
            assertEquals(RpmZone.NORMAL, zones.first())
            assertEquals(RpmZone.DANGER, zones.last())
            assertEquals(zones, zones.sortedBy { it.ordinal })
        }
    }

    @Test
    fun `a segment lights as soon as the rpm passes its start`() {
        assertEquals(0, litSegmentCount(0.0, 40))
        assertEquals(1, litSegmentCount(1.0, 40))
        assertEquals(1, litSegmentCount(200.0, 40))
        assertEquals(2, litSegmentCount(201.0, 40))
        assertEquals(5, litSegmentCount(830.0, 40))
    }

    @Test
    fun `lit segments stick to the ends of the bar`() {
        assertEquals(0, litSegmentCount(-50.0, 30))
        assertEquals(30, litSegmentCount(6_000.0, 30))
        assertEquals(30, litSegmentCount(12_000.0, 30))
        assertEquals(55, litSegmentCount(11_000.0, 55))
    }

    @Test
    fun `on a fast link the bar takes as long as the measured interval between readings`() {
        assertEquals(250, sweepDurationMs(4.0))
        assertEquals(125, sweepDurationMs(8.0))
    }

    @Test
    fun `on a slow link the bar arrives within 350 ms instead of trailing a whole interval`() {
        assertEquals(350, sweepDurationMs(2.0))
        assertEquals(350, sweepDurationMs(0.5))
    }

    @Test
    fun `sweep duration is clamped between 100 and 350 ms`() {
        assertEquals(100, sweepDurationMs(50.0))
        assertEquals(350, sweepDurationMs(0.2))
    }

    @Test
    fun `sweep duration falls back to the longest glide before anything is measured`() {
        assertEquals(350, sweepDurationMs(null))
        assertEquals(350, sweepDurationMs(0.0))
    }
}
