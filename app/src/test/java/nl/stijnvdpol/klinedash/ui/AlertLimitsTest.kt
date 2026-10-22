package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.data.UnitSystem
import nl.stijnvdpol.klinedash.core.alert.AlertSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertLimitsTest {
    private val defaults = AlertSettings()

    @Test
    fun `coolant steps by one degree`() {
        assertEquals(104.0, defaults.stepped(AlertLimit.COOLANT_CAUTION, 1, UnitSystem.METRIC).coolantCautionC, 0.0)
        assertEquals(102.0, defaults.stepped(AlertLimit.COOLANT_CAUTION, -1, UnitSystem.METRIC).coolantCautionC, 0.0)
        assertEquals(109.0, defaults.stepped(AlertLimit.COOLANT_DANGER, 1, UnitSystem.METRIC).coolantDangerC, 0.0)
    }

    @Test
    fun `voltage steps by a tenth of a volt without float drift`() {
        var settings = defaults
        repeat(7) { settings = settings.stepped(AlertLimit.VOLTAGE_RUNNING_LOW, -1, UnitSystem.METRIC) }
        assertEquals(12.5, settings.voltageRunningLow, 0.0)
        repeat(3) { settings = settings.stepped(AlertLimit.VOLTAGE_RUNNING_LOW, 1, UnitSystem.METRIC) }
        assertEquals(12.8, settings.voltageRunningLow, 0.0)
    }

    @Test
    fun `a step changes only its own limit`() {
        val stepped = defaults.stepped(AlertLimit.VOLTAGE_ENGINE_OFF_LOW, -1, UnitSystem.METRIC)
        assertEquals(defaults.copy(voltageEngineOffLow = 11.9), stepped)
    }

    @Test
    fun `caution cannot reach danger`() {
        val tight = defaults.copy(coolantCautionC = 107.0, coolantDangerC = 108.0)
        assertFalse(tight.canStep(AlertLimit.COOLANT_CAUTION, 1, UnitSystem.METRIC))
        assertEquals(tight, tight.stepped(AlertLimit.COOLANT_CAUTION, 1, UnitSystem.METRIC))
        assertTrue(tight.canStep(AlertLimit.COOLANT_CAUTION, -1, UnitSystem.METRIC))
    }

    @Test
    fun `danger cannot drop to caution`() {
        val tight = defaults.copy(coolantCautionC = 107.0, coolantDangerC = 108.0)
        assertFalse(tight.canStep(AlertLimit.COOLANT_DANGER, -1, UnitSystem.METRIC))
        assertEquals(tight, tight.stepped(AlertLimit.COOLANT_DANGER, -1, UnitSystem.METRIC))
        assertTrue(tight.canStep(AlertLimit.COOLANT_DANGER, 1, UnitSystem.METRIC))
    }

    @Test
    fun `raising caution follows danger up only when danger moves first`() {
        val raised = defaults.stepped(AlertLimit.COOLANT_DANGER, 1, UnitSystem.METRIC).stepped(AlertLimit.COOLANT_CAUTION, 1, UnitSystem.METRIC)
        assertEquals(104.0, raised.coolantCautionC, 0.0)
        assertEquals(109.0, raised.coolantDangerC, 0.0)
    }

    @Test
    fun `every limit stops at the ends of its range`() {
        AlertLimit.entries.forEach { limit ->
            var down = defaults
            repeat(1_000) { down = down.stepped(limit, -1, UnitSystem.METRIC) }
            assertTrue("$limit stays at or above its minimum", down.valueOf(limit) >= limit.min)
            assertFalse("$limit cannot go lower", down.canStep(limit, -1, UnitSystem.METRIC))

            var up = defaults
            repeat(1_000) { up = up.stepped(limit, 1, UnitSystem.METRIC) }
            assertTrue("$limit stays at or below its maximum", up.valueOf(limit) <= limit.max)
            assertFalse("$limit cannot go higher", up.canStep(limit, 1, UnitSystem.METRIC))
        }
    }

    @Test
    fun `coolant limits reach the full range when the other one is out of the way`() {
        var low = defaults
        repeat(100) { low = low.stepped(AlertLimit.COOLANT_CAUTION, -1, UnitSystem.METRIC) }
        assertEquals(AlertLimit.COOLANT_CAUTION.min, low.coolantCautionC, 0.0)
        var high = defaults
        repeat(100) { high = high.stepped(AlertLimit.COOLANT_DANGER, 1, UnitSystem.METRIC) }
        assertEquals(AlertLimit.COOLANT_DANGER.max, high.coolantDangerC, 0.0)
    }

    @Test
    fun `the simulator temperature is within reach of the caution limit`() {
        // The test guide lowers caution below the simulated ~90 degrees; that has to be possible.
        assertTrue(AlertLimit.COOLANT_CAUTION.min < 85.0)
    }

    @Test
    fun `the default limits are inside their ranges`() {
        AlertLimit.entries.forEach { limit ->
            assertTrue("$limit", defaults.valueOf(limit) in limit.min..limit.max)
        }
        assertTrue(defaults.coolantCautionC < defaults.coolantDangerC)
    }

    @Test
    fun `a stored value outside the range is pulled back by the next step`() {
        val corrupt = defaults.copy(voltageRunningHigh = 40.0)
        assertEquals(AlertLimit.VOLTAGE_RUNNING_HIGH.max, corrupt.stepped(AlertLimit.VOLTAGE_RUNNING_HIGH, -1, UnitSystem.METRIC).voltageRunningHigh, 0.0)
    }

    // --- imperial: the user steps in Fahrenheit, the stored value stays Celsius ------------------

    private val imperial = UnitSystem.IMPERIAL

    @Test
    fun `the limit is shown in Fahrenheit, rounded like the display`() {
        assertEquals(217.0, defaults.shownValue(AlertLimit.COOLANT_CAUTION, imperial), 0.0)
        assertEquals(103.0, defaults.shownValue(AlertLimit.COOLANT_CAUTION, UnitSystem.METRIC), 0.0)
        assertEquals("°F", AlertLimit.COOLANT_CAUTION.unitIn(imperial))
        assertEquals("°C", AlertLimit.COOLANT_CAUTION.unitIn(UnitSystem.METRIC))
        assertEquals("V", AlertLimit.VOLTAGE_RUNNING_LOW.unitIn(imperial))
    }

    @Test
    fun `a Fahrenheit step is one Fahrenheit degree and is stored as Celsius`() {
        val up = defaults.stepped(AlertLimit.COOLANT_CAUTION, 1, imperial)
        assertEquals(218.0, up.shownValue(AlertLimit.COOLANT_CAUTION, imperial), 0.0)
        assertEquals((218.0 - 32.0) / 1.8, up.coolantCautionC, 1e-9)
        val down = defaults.stepped(AlertLimit.COOLANT_CAUTION, -1, imperial)
        assertEquals(216.0, down.shownValue(AlertLimit.COOLANT_CAUTION, imperial), 0.0)
    }

    @Test
    fun `many Fahrenheit steps never drift or skip a degree`() {
        var settings = defaults
        repeat(20) { settings = settings.stepped(AlertLimit.COOLANT_CAUTION, -1, imperial) }
        assertEquals(197.0, settings.shownValue(AlertLimit.COOLANT_CAUTION, imperial), 0.0)
        repeat(20) { settings = settings.stepped(AlertLimit.COOLANT_CAUTION, 1, imperial) }
        assertEquals(217.0, settings.shownValue(AlertLimit.COOLANT_CAUTION, imperial), 0.0)
    }

    @Test
    fun `caution stays one Fahrenheit degree below danger`() {
        var settings = defaults
        repeat(100) { settings = settings.stepped(AlertLimit.COOLANT_CAUTION, 1, imperial) }
        val caution = settings.shownValue(AlertLimit.COOLANT_CAUTION, imperial)
        val danger = settings.shownValue(AlertLimit.COOLANT_DANGER, imperial)
        assertEquals(danger - 1.0, caution, 0.0)
        assertFalse(settings.canStep(AlertLimit.COOLANT_CAUTION, 1, imperial))
    }

    @Test
    fun `the end stops of the range are whole Fahrenheit degrees, and the end stop is not a step`() {
        var down = defaults
        repeat(1_000) { down = down.stepped(AlertLimit.COOLANT_CAUTION, -1, imperial) }
        assertEquals(140.0, down.shownValue(AlertLimit.COOLANT_CAUTION, imperial), 0.0)
        assertFalse(down.canStep(AlertLimit.COOLANT_CAUTION, -1, imperial))
        assertEquals(down, down.stepped(AlertLimit.COOLANT_CAUTION, -1, imperial))
        var up = defaults
        repeat(1_000) { up = up.stepped(AlertLimit.COOLANT_DANGER, 1, imperial) }
        assertEquals(266.0, up.shownValue(AlertLimit.COOLANT_DANGER, imperial), 0.0)
        assertFalse(up.canStep(AlertLimit.COOLANT_DANGER, 1, imperial))
    }

    @Test
    fun `volts do not change with the unit system`() {
        val metricStep = defaults.stepped(AlertLimit.VOLTAGE_RUNNING_LOW, -1, UnitSystem.METRIC)
        val imperialStep = defaults.stepped(AlertLimit.VOLTAGE_RUNNING_LOW, -1, imperial)
        assertEquals(metricStep, imperialStep)
        assertEquals(1, AlertLimit.VOLTAGE_RUNNING_LOW.decimalsIn(imperial))
        assertEquals(0, AlertLimit.COOLANT_CAUTION.decimalsIn(imperial))
    }
}
