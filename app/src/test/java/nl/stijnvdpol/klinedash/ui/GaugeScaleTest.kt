package nl.stijnvdpol.klinedash.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GaugeScaleTest {

    @Test
    fun `fraction sticks to the ends of the scale`() {
        assertEquals(0f, GaugeScale.fraction(-50.0, 9000.0), 0f)
        assertEquals(0.5f, GaugeScale.fraction(4500.0, 9000.0), 0f)
        assertEquals(1f, GaugeScale.fraction(12000.0, 9000.0), 0f)
    }

    @Test
    fun `sweep runs from bottom left over the top to bottom right`() {
        assertEquals(150f, GaugeScale.angleFor(0f), 0f)
        assertEquals(270f, GaugeScale.angleFor(0.5f), 0f)
        assertEquals(390f, GaugeScale.angleFor(1f), 0f)
    }

    @Test
    fun `needle takes as long as the measured interval between readings`() {
        assertEquals(400, needleDurationMs(2.5))
        assertEquals(1000, needleDurationMs(1.0))
    }

    @Test
    fun `needle duration is clamped between 100 and 1000 ms`() {
        assertEquals(100, needleDurationMs(50.0))
        assertEquals(1000, needleDurationMs(0.2))
    }

    @Test
    fun `needle duration falls back to a default before anything is measured`() {
        assertEquals(500, needleDurationMs(null))
        assertEquals(500, needleDurationMs(0.0))
    }
}
