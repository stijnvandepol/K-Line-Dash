package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.core.obd.PollMode
import nl.stijnvdpol.klinedash.core.obd.PollStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavBarTest {
    @Test
    fun `an active link shows its request rate with one decimal`() {
        val rate = linkRateOf(PollStats(mode = PollMode.ACTIVE, requestsPerSecond = 6.64))
        assertEquals("6.6", rate.rateText)
        assertFalse(rate.isEngineOff)
    }

    @Test
    fun `stats that differ only in what the chip does not show give an equal result`() {
        val a = PollStats(mode = PollMode.ACTIVE, requestsPerSecond = 6.62, meanRoundTripMs = 140.0)
        val b = PollStats(mode = PollMode.ACTIVE, requestsPerSecond = 6.58, meanRoundTripMs = 151.0, signalHz = mapOf("rpm" to 2.0))
        assertEquals(linkRateOf(a), linkRateOf(b))
    }

    @Test
    fun `engine off is flagged and shows no rate`() {
        val rate = linkRateOf(PollStats(mode = PollMode.ENGINE_OFF, requestsPerSecond = 0.4))
        assertTrue(rate.isEngineOff)
        assertEquals(MISSING_VALUE, rate.rateText)
    }

    @Test
    fun `any other mode shows a missing rate`() {
        val rate = linkRateOf(PollStats(mode = PollMode.WAITING, requestsPerSecond = 3.0))
        assertFalse(rate.isEngineOff)
        assertEquals(MISSING_VALUE, rate.rateText)
    }
}
