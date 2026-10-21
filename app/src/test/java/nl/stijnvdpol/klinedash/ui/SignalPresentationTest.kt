package nl.stijnvdpol.klinedash.ui

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
        assertEquals("8888", slotSample(signal("rpm")))
        assertEquals("888", slotSample(signal("coolant")))
        assertEquals("-88", slotSample(signal("intake_temp")))
        assertEquals("88.8", slotSample(signal("battery")))
    }

    @Test
    fun `readings are formatted with the decimals of the signal`() {
        assertEquals("88", formatReading(signal("coolant"), 88.0, words))
        assertEquals("14.5", formatReading(signal("timing"), 14.5, words))
    }

    @Test
    fun `thousands are not grouped, so a value reads as one number`() {
        assertEquals("8175", formatReading(signal("rpm"), 8175.0, words))
        assertEquals("830", formatReading(signal("rpm"), 830.0, words))
    }

    @Test
    fun `the warning lamp reads on or off instead of a number`() {
        assertEquals("On", formatReading(signal("mil_on"), 1.0, words))
        assertEquals("Off", formatReading(signal("mil_on"), 0.0, words))
    }

    @Test
    fun `counts stay numbers even when they are 0 or 1`() {
        assertEquals("1", formatReading(signal("dtc_count"), 1.0, words))
    }

    @Test
    fun `coolant turns amber at 100 and red at 105 degrees`() {
        assertEquals(ValueTone.NORMAL, valueTone("coolant", 99.0, engineRunning = true))
        assertEquals(ValueTone.CAUTION, valueTone("coolant", 100.0, engineRunning = true))
        assertEquals(ValueTone.CAUTION, valueTone("coolant", 104.0, engineRunning = true))
        assertEquals(ValueTone.DANGER, valueTone("coolant", 105.0, engineRunning = true))
    }

    @Test
    fun `battery is amber below 12 V and above 15 V while the engine runs`() {
        assertEquals(ValueTone.NORMAL, valueTone("battery", 13.9, engineRunning = true))
        assertEquals(ValueTone.NORMAL, valueTone("battery", 12.0, engineRunning = true))
        assertEquals(ValueTone.CAUTION, valueTone("battery", 11.9, engineRunning = true))
        assertEquals(ValueTone.NORMAL, valueTone("battery", 15.0, engineRunning = true))
        assertEquals(ValueTone.CAUTION, valueTone("battery", 15.1, engineRunning = true))
    }

    @Test
    fun `battery has no limits while the engine is off`() {
        assertEquals(ValueTone.NORMAL, valueTone("battery", 11.2, engineRunning = false))
    }

    @Test
    fun `signals without limits are never coloured`() {
        assertEquals(ValueTone.NORMAL, valueTone("rpm", 8_900.0, engineRunning = true))
        assertEquals(ValueTone.NORMAL, valueTone("intake_temp", 90.0, engineRunning = true))
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
}
