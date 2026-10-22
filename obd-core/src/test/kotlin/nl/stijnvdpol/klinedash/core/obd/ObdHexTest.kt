package nl.stijnvdpol.klinedash.core.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObdHexTest {

    @Test
    fun `hex text becomes bytes in either case`() {
        assertArrayEquals(intArrayOf(0x41, 0x0C, 0x1A, 0xF8), ObdHex.toBytes("410C1AF8"))
        assertArrayEquals(intArrayOf(0x1A, 0xF8), ObdHex.toBytes("1af8"))
        assertArrayEquals(intArrayOf(), ObdHex.toBytes(""))
    }

    @Test
    fun `odd length and anything but hex digits are rejected`() {
        assertNull(ObdHex.toBytes("410"))
        assertNull(ObdHex.toBytes("41 0C"))
        assertNull(ObdHex.toBytes("41G0"))
        assertNull(ObdHex.toBytes("0:41"))
        // Character.digit would accept these full-width digits; a reply never contains them.
        assertNull(ObdHex.toBytes("４１"))
    }

    @Test
    fun `requests are two upper-case digits per byte`() {
        assertEquals("010C", ObdHex.request(0x01, 0x0C))
        assertEquals("020C00", ObdHex.request(0x02, 0x0C, 0x00))
        assertEquals("0A", ObdHex.request(0x0A))
        assertEquals("010C0D11", ObdHex.request(0x01, 0x0C, 0x0D, 0x11))
    }

    @Test
    fun `a positive response adds 0x40 to the service`() {
        assertEquals(0x41, ObdHex.responseService(0x01))
        assertEquals(0x43, ObdHex.responseService(0x03))
        assertEquals(0x49, ObdHex.responseService(0x09))
    }
}
