package nl.stijnvdpol.klinedash.core.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBufferTest {

    private fun bytes(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `single response with prompt`() {
        val buffer = PromptBuffer()
        assertEquals(listOf("41 0C 1A F8 \r\r"), buffer.feed(bytes("41 0C 1A F8 \r\r>")))
    }

    @Test
    fun `response split across chunks completes on the prompt chunk`() {
        val buffer = PromptBuffer()
        assertTrue(buffer.feed(bytes("41 0C ")).isEmpty())
        assertEquals("41 0C ", buffer.pending())
        assertTrue(buffer.feed(bytes("1A F8")).isEmpty())
        assertTrue(buffer.feed(bytes("\r\r")).isEmpty())
        assertEquals(listOf("41 0C 1A F8\r\r"), buffer.feed(bytes(">")))
        assertEquals("", buffer.pending())
    }

    @Test
    fun `chunk boundary between cr and lf`() {
        val buffer = PromptBuffer()
        assertTrue(buffer.feed(bytes("OK\r")).isEmpty())
        assertEquals(listOf("OK\r\n\r\n"), buffer.feed(bytes("\n\r\n>")))
    }

    @Test
    fun `multiple prompts in one chunk give one response each`() {
        val buffer = PromptBuffer()
        val out = buffer.feed(bytes("OK\r\r>OK\r\r>41 00 BE 1F B8 10\r\r>"))
        assertEquals(listOf("OK\r\r", "OK\r\r", "41 00 BE 1F B8 10\r\r"), out)
    }

    @Test
    fun `text after the last prompt stays pending`() {
        val buffer = PromptBuffer()
        assertEquals(listOf("OK\r\r"), buffer.feed(bytes("OK\r\r>41 0C")))
        assertEquals("41 0C", buffer.pending())
        assertEquals(listOf("41 0C 1A F8\r\r"), buffer.feed(bytes(" 1A F8\r\r>")))
    }

    @Test
    fun `empty response between two prompts`() {
        val buffer = PromptBuffer()
        assertEquals(listOf("", "OK\r\r"), buffer.feed(bytes(">OK\r\r>")))
    }

    @Test
    fun `nul and high bit bytes are dropped`() {
        val buffer = PromptBuffer()
        val input = byteArrayOf(0x00, 0xFF.toByte(), 0x34, 0x31, 0x80.toByte(), 0x20, 0x30, 0x00, 0x43, 0x0D, 0x0D, 0x3E)
        assertEquals(listOf("41 0C\r\r"), buffer.feed(input))
    }

    @Test
    fun `other control characters are dropped but cr and lf are kept`() {
        val buffer = PromptBuffer()
        assertEquals(listOf("OK\r\n"), buffer.feed(bytes("O\u0007K\r\n\u001b>")))
    }

    @Test
    fun `length parameter limits the bytes consumed`() {
        val buffer = PromptBuffer()
        val data = bytes("41 0C\r\r>GARBAGE")
        assertEquals(listOf("41 0C\r\r"), buffer.feed(data, 8))
        assertEquals("", buffer.pending())
    }

    @Test
    fun `clear discards partial text`() {
        val buffer = PromptBuffer()
        buffer.feed(bytes("41 0C"))
        buffer.clear()
        assertEquals("", buffer.pending())
        assertEquals(listOf("OK\r"), buffer.feed(bytes("OK\r>")))
    }
}
