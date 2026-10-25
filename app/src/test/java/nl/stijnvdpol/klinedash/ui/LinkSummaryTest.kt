package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.core.obd.PollStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkSummaryTest {

    @Test
    fun `the summary gives the request rate and the whole milliseconds per request`() {
        assertEquals("2.1" to "476", linkSummaryText(PollStats(requestsPerSecond = 2.08, meanRoundTripMs = 476.4)))
    }

    @Test
    fun `nothing is shown before a request was answered`() {
        assertNull(linkSummaryText(PollStats(requestsPerSecond = 0.0, meanRoundTripMs = 0.0)))
    }
}
