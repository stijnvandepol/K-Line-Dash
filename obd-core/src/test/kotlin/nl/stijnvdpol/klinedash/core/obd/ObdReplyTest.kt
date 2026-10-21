package nl.stijnvdpol.klinedash.core.obd

import nl.stijnvdpol.klinedash.core.elm.ElmResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObdReplyTest {

    private fun data(vararg lines: String) = ElmResult.Data(lines.toList(), lines.joinToString("\r"))

    @Test
    fun `payload returns the bytes after service and pid`() {
        assertArrayEquals(intArrayOf(0x1A, 0xF8), ObdReply.payload(data("410C1AF8"), 1, 0x0C))
    }

    @Test
    fun `payload accepts lower case hex`() {
        assertArrayEquals(intArrayOf(0x1A, 0xF8), ObdReply.payload(data("410c1af8"), 1, 0x0C))
    }

    @Test
    fun `payload is empty array for a line without data bytes`() {
        assertArrayEquals(intArrayOf(), ObdReply.payload(data("410C"), 1, 0x0C))
    }

    @Test
    fun `payload uses the response service of the requested mode`() {
        assertArrayEquals(intArrayOf(0x05), ObdReply.payload(data("420C05"), 2, 0x0C))
        assertNull(ObdReply.payload(data("420C05"), 1, 0x0C))
    }

    @Test
    fun `payload is null for another pid`() {
        assertNull(ObdReply.payload(data("410D32"), 1, 0x0C))
    }

    @Test
    fun `payload skips foreign, odd and invalid lines and finds the matching one`() {
        val reply = data("410D32", "410C1AF", "410CZZ11", "41", "", "410C1AF8")
        assertArrayEquals(intArrayOf(0x1A, 0xF8), ObdReply.payload(reply, 1, 0x0C))
    }

    @Test
    fun `payload returns the first of several matching lines`() {
        assertArrayEquals(intArrayOf(0x11), ObdReply.payload(data("410511", "410522"), 1, 0x05))
    }

    @Test
    fun `payload is null when no line matches`() {
        assertNull(ObdReply.payload(data("7F0112"), 1, 0x0C))
    }

    @Test
    fun `payloads returns every matching line in order`() {
        val reply = data("4100BE1FF811", "410D32", "4100801000FF")
        val all = ObdReply.payloads(reply, 1, 0x00)
        assertEquals(2, all.size)
        assertArrayEquals(intArrayOf(0xBE, 0x1F, 0xF8, 0x11), all[0])
        assertArrayEquals(intArrayOf(0x80, 0x10, 0x00, 0xFF), all[1])
    }

    @Test
    fun `payloads is empty when nothing matches`() {
        assertEquals(emptyList<IntArray>(), ObdReply.payloads(data("410D32", "XYZ"), 1, 0x00))
    }
}
