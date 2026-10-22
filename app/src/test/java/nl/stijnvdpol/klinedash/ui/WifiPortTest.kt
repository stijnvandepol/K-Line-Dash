package nl.stijnvdpol.klinedash.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiPortTest {
    @Test
    fun `the port field keeps digits only, at most five`() {
        assertEquals("35000", portDigits("35000"))
        assertEquals("350", portDigits("3a5-0"))
        assertEquals("12345", portDigits("1234567"))
        assertEquals("", portDigits("port"))
    }

    @Test
    fun `only a usable TCP port is stored`() {
        assertEquals(35000, validPort("35000"))
        assertEquals(1, validPort("1"))
        assertEquals(65535, validPort("65535"))
        assertNull(validPort("0"))
        assertNull(validPort("65536"))
        assertNull(validPort(""))
    }
}
