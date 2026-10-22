package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.data.UnitSystem
import nl.stijnvdpol.klinedash.core.alert.AlertSettings
import nl.stijnvdpol.klinedash.core.obd.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalPresentationTest {
    private fun signal(id: String) = checkNotNull(Signals.byId(id))
    private val words = OnOffWords(on = "On", off = "Off")

    @Test
    fun `fast signals go stale after 5 seconds`() {
        assertFalse(isStale(signal("rpm"), readingTimestampMs = 1_000, nowMs = 6_000))
        assertTrue(isStale(signal("rpm"), readingTimestampMs = 1_000, nowMs = 6_001))
    }

    @Test
    fun `slower tiers stay fresh longer than their poll interval`() {
        // Coolant is polled every 3 s and battery every 15 s; both must survive a normal gap.
        assertFalse(isStale(signal("coolant"), 0, nowMs = 10_000))
        assertFalse(isStale(signal("battery"), 0, nowMs = 40_000))
        assertTrue(isStale(signal("battery"), 0, nowMs = 46_000))
    }

    @Test
    fun `slot sample has the width of the widest value`() {
        assertEquals("8888", slotSample(signal("rpm"), UnitSystem.METRIC))
        assertEquals("888", slotSample(signal("coolant"), UnitSystem.METRIC))
        assertEquals("-88", slotSample(signal("intake_temp"), UnitSystem.METRIC))
        assertEquals("88.8", slotSample(signal("battery"), UnitSystem.METRIC))
    }

    @Test
    fun `readings are formatted with the decimals of the signal`() {
        assertEquals("88", formatReading(signal("coolant"), 88.0, words, UnitSystem.METRIC))
        assertEquals("14.5", formatReading(signal("timing"), 14.5, words, UnitSystem.METRIC))
    }

    @Test
    fun `thousands are not grouped, so a value reads as one number`() {
        assertEquals("8175", formatReading(signal("rpm"), 8175.0, words, UnitSystem.METRIC))
        assertEquals("830", formatReading(signal("rpm"), 830.0, words, UnitSystem.METRIC))
    }

    @Test
    fun `the warning lamp reads on or off instead of a number`() {
        assertEquals("On", formatReading(signal("mil_on"), 1.0, words, UnitSystem.METRIC))
        assertEquals("Off", formatReading(signal("mil_on"), 0.0, words, UnitSystem.METRIC))
    }

    @Test
    fun `counts stay numbers even when they are 0 or 1`() {
        assertEquals("1", formatReading(signal("dtc_count"), 1.0, words, UnitSystem.METRIC))
    }

    private val defaults = AlertSettings()
    private fun running(limits: AlertSettings = defaults) = ToneContext(engineRunning = true, limits = limits)
    private fun stopped(limits: AlertSettings = defaults) = ToneContext(engineRunning = false, limits = limits)

    @Test
    fun `coolant turns amber at the caution limit and red at the danger limit`() {
        // Defaults: caution 103, danger 108.
        assertEquals(ValueTone.NORMAL, valueTone("coolant", 102.0, running()))
        assertEquals(ValueTone.CAUTION, valueTone("coolant", 103.0, running()))
        assertEquals(ValueTone.CAUTION, valueTone("coolant", 107.0, running()))
        assertEquals(ValueTone.DANGER, valueTone("coolant", 108.0, running()))
    }

    @Test
    fun `coolant colours move with the limits from the warning settings`() {
        val lowered = defaults.copy(coolantCautionC = 85.0, coolantDangerC = 90.0)
        assertEquals(ValueTone.CAUTION, valueTone("coolant", 88.0, running(lowered)))
        assertEquals(ValueTone.DANGER, valueTone("coolant", 90.0, running(lowered)))
        assertEquals(ValueTone.NORMAL, valueTone("coolant", 88.0, running()))
    }

    @Test
    fun `battery is amber outside the running limits while the engine runs`() {
        // Defaults: low 13.2, high 15.0.
        assertEquals(ValueTone.NORMAL, valueTone("battery", 13.9, running()))
        assertEquals(ValueTone.NORMAL, valueTone("battery", 13.2, running()))
        assertEquals(ValueTone.CAUTION, valueTone("battery", 13.1, running()))
        assertEquals(ValueTone.NORMAL, valueTone("battery", 15.0, running()))
        assertEquals(ValueTone.CAUTION, valueTone("battery", 15.1, running()))
    }

    @Test
    fun `battery uses the engine-off limit while the engine is off and ignores the charging limits`() {
        // Defaults: engine-off low 12.0. A resting battery at 12.6 V is healthy, not "below 13.2".
        assertEquals(ValueTone.NORMAL, valueTone("battery", 12.6, stopped()))
        assertEquals(ValueTone.NORMAL, valueTone("battery", 12.0, stopped()))
        assertEquals(ValueTone.CAUTION, valueTone("battery", 11.9, stopped()))
        assertEquals(ValueTone.NORMAL, valueTone("battery", 15.5, stopped()))
    }

    @Test
    fun `battery colours move with the limits from the warning settings`() {
        val strict = defaults.copy(voltageRunningLow = 13.8, voltageEngineOffLow = 12.5)
        assertEquals(ValueTone.CAUTION, valueTone("battery", 13.5, running(strict)))
        assertEquals(ValueTone.CAUTION, valueTone("battery", 12.4, stopped(strict)))
    }

    @Test
    fun `signals without limits are never coloured`() {
        assertEquals(ValueTone.NORMAL, valueTone("rpm", 8_900.0, running()))
        assertEquals(ValueTone.NORMAL, valueTone("intake_temp", 90.0, running()))
    }

    @Test
    fun `the engine counts as running above cranking speed`() {
        assertFalse(isEngineRunning(null))
        assertFalse(isEngineRunning(0.0))
        assertFalse(isEngineRunning(250.0))
        assertTrue(isEngineRunning(830.0))
    }

    @Test
    fun `adapter commands are always available, PIDs only when supported`() {
        assertTrue(signal("battery").isAvailable(emptySet()))
        assertFalse(signal("coolant").isAvailable(emptySet()))
        assertTrue(signal("coolant").isAvailable(setOf(0x05)))
    }

    @Test
    fun `sensor list holds exactly the supported signals`() {
        val ids = supportedSignals(setOf(0x05, 0x0C)).map { it.id }.toSet()
        assertEquals(setOf("rpm", "coolant", "battery"), ids)
    }

    // --- imperial ---------------------------------------------------------------------------------

    @Test
    fun `readings are shown in the chosen units, and only those that have another unit change`() {
        val imperial = UnitSystem.IMPERIAL
        assertEquals("62", formatReading(signal("speed"), 100.0, words, imperial))
        assertEquals("190", formatReading(signal("coolant"), 88.0, words, imperial))
        assertEquals("14.6", formatReading(signal("map"), 101.0, words, imperial))
        assertEquals("8175", formatReading(signal("rpm"), 8175.0, words, imperial))
        assertEquals("13.9", formatReading(signal("battery"), 13.9, words, imperial))
        assertEquals("On", formatReading(signal("mil_on"), 1.0, words, imperial))
    }

    @Test
    fun `the unit next to a value follows the system`() {
        assertEquals("km/h", unitOf(signal("speed"), UnitSystem.METRIC))
        assertEquals("mph", unitOf(signal("speed"), UnitSystem.IMPERIAL))
        assertEquals("°F", unitOf(signal("coolant"), UnitSystem.IMPERIAL))
        assertEquals("rpm", unitOf(signal("rpm"), UnitSystem.IMPERIAL))
    }

    @Test
    fun `an imperial slot is as wide as its widest value`() {
        assertEquals("888", slotSample(signal("speed"), UnitSystem.IMPERIAL))
        assertEquals("888", slotSample(signal("coolant"), UnitSystem.IMPERIAL))
        assertEquals("888", slotSample(signal("intake_temp"), UnitSystem.IMPERIAL))
        assertEquals("888.8", slotSample(signal("fuel_pressure"), UnitSystem.IMPERIAL))
    }
}
