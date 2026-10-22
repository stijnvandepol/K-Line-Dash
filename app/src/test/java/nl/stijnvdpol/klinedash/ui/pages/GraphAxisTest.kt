package nl.stijnvdpol.klinedash.ui.pages

import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.data.UnitSystem
import nl.stijnvdpol.klinedash.service.SignalDisplay
import nl.stijnvdpol.klinedash.service.display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphAxisTest {
    private fun signal(id: String): SignalDisplay = checkNotNull(Signals.byId(id)).display(UnitSystem.METRIC)

    private fun assertAxis(expectedLow: Double, expectedHigh: Double, axis: ValueRange) {
        assertEquals("low", expectedLow, axis.low, 1e-9)
        assertEquals("high", expectedHigh, axis.high, 1e-9)
    }

    @Test
    fun `a coolant temperature that moves 2 degrees does not fill the cell`() {
        val coolant = signal("coolant")
        val axis = graphAxis(ValueRange(85.0, 87.0), coolant)
        assertAxis(78.0, 94.0, axis)
        val span = axis.high - axis.low
        assertTrue("at least 15 % of 40..130 °C", span >= (coolant.max - coolant.min) * MIN_AXIS_SPAN_SHARE)
        assertTrue("the 2 °C use under a fifth of the height", 2.0 / span < 0.2)
    }

    @Test
    fun `a wide range is rounded to whole steps but not past the display range`() {
        assertAxis(0.0, 9000.0, graphAxis(ValueRange(838.0, 8169.0), signal("rpm")))
        assertAxis(0.0, 160.0, graphAxis(ValueRange(0.0, 143.0), signal("speed")))
    }

    @Test
    fun `a flat zero stays at the bottom instead of getting a negative axis`() {
        assertAxis(0.0, 40.0, graphAxis(ValueRange(0.0, 0.0), signal("speed")))
    }

    @Test
    fun `decimal signals get decimal steps`() {
        assertAxis(13.2, 14.6, graphAxis(ValueRange(13.9, 13.9), signal("battery")))
        assertAxis(-4.0, 4.0, graphAxis(ValueRange(-0.8, 0.8), signal("stft_b1")))
    }

    @Test
    fun `data beyond the display range is still on the axis`() {
        assertAxis(120.0, 136.0, graphAxis(ValueRange(120.0, 135.0), signal("coolant")))
    }

    @Test
    fun `an axis in Fahrenheit has round Fahrenheit labels, not converted Celsius ones`() {
        val coolant = checkNotNull(Signals.byId("coolant")).display(UnitSystem.IMPERIAL)
        // 85 to 87 degrees Celsius is 185 to 188.6 Fahrenheit.
        val axis = graphAxis(ValueRange(185.0, 188.6), coolant)
        assertEquals(axis.low, Math.floor(axis.low / 5) * 5, 1e-9)
        assertEquals(axis.high, Math.floor(axis.high / 5) * 5, 1e-9)
        assertTrue(axis.low <= 185.0 && axis.high >= 188.6)
        assertTrue("at least 15 % of 104..266 °F", axis.high - axis.low >= (coolant.max - coolant.min) * MIN_AXIS_SPAN_SHARE)
    }

    @Test
    fun `a flat zero speed in mph stays at the bottom`() {
        assertAxis(0.0, 25.0, graphAxis(ValueRange(0.0, 0.0), checkNotNull(Signals.byId("speed")).display(UnitSystem.IMPERIAL)))
    }

    @Test
    fun `steps are one, two or five times a power of ten`() {
        assertEquals(2.0, niceStep(2.7), 1e-9)
        assertEquals(2000.0, niceStep(1466.0), 1e-9)
        assertEquals(10.0, niceStep(7.2), 1e-9)
        assertEquals(0.5, niceStep(0.4), 1e-9)
        assertEquals(1.0, niceStep(1.0), 1e-9)
    }
}
