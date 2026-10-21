package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.core.obd.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalPresentationTest {
    private fun signal(id: String) = checkNotNull(Signals.byId(id))

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
        assertEquals("8,888", slotSample(signal("rpm")))
        assertEquals("888", slotSample(signal("coolant")))
        assertEquals("-88", slotSample(signal("intake_temp")))
        assertEquals("88.8", slotSample(signal("battery")))
    }

    @Test
    fun `readings are formatted with the decimals of the signal`() {
        assertEquals("88", formatReading(signal("coolant"), 88.0))
        assertEquals("14.5", formatReading(signal("timing"), 14.5))
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
