package nl.stijnvdpol.klinedash.service

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatNumberTest {

    @Test
    fun `uses a decimal point`() {
        assertEquals("13.9", formatNumber(13.94, 1))
        assertEquals("13.9", formatVoltage(13.94))
    }

    @Test
    fun `groups thousands with a comma`() {
        assertEquals("3,450", formatNumber(3450.0, 0))
        assertEquals("65,535", formatNumber(65535.0, 0))
        assertEquals("830", formatNumber(830.0, 0))
    }

    @Test
    fun `rounds to the requested decimals`() {
        assertEquals("88", formatNumber(87.6, 0))
        assertEquals("0.35", formatNumber(0.354, 2))
    }

    @Test
    fun `keeps the minus sign of real negative values`() {
        assertEquals("-4.5", formatNumber(-4.5, 1))
        assertEquals("-12", formatNumber(-12.2, 0))
    }

    @Test
    fun `never shows a negative zero`() {
        assertEquals("0.0", formatNumber(-0.04, 1))
        assertEquals("0", formatNumber(-0.4, 0))
        assertEquals("0", formatNumber(-0.0, 0))
    }
}
