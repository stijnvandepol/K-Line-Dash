package nl.stijnvdpol.klinedash.core.export

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import nl.stijnvdpol.klinedash.core.assertFails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestReaderTest {

    /** Hands out [bytes] one per read, each after [delayMs]; the ideal slow-drip client. */
    private class DripInput(private val bytes: ByteArray, private val delayMs: Long) : InputStream() {
        private var next = 0

        override fun read(): Int {
            if (next >= bytes.size) return -1
            Thread.sleep(delayMs)
            return bytes[next++].toInt() and 0xFF
        }
    }

    private fun request(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun `every read is armed with a timeout no longer than the cap`() {
        val timeouts = ArrayList<Int>()

        RequestReader.read(request("GET / HTTP/1.1\r\n\r\n"), maxReadTimeoutMs = READ_CAP_MS) { timeouts += it }

        assertTrue(timeouts.isNotEmpty())
        assertTrue("timeouts $timeouts", timeouts.all { it in MIN_READ_TIMEOUT_MS..READ_CAP_MS })
    }

    @Test
    fun `the read timeout shrinks to the time left until the deadline`() {
        val timeouts = ArrayList<Int>()
        val deadlineNanos = TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS)

        assertFails<IOException> {
            RequestReader.read(
                DripInput("GET / HTTP/1.1\r\n\r\n".toByteArray(), DRIP_DELAY_MS),
                maxReadTimeoutMs = READ_CAP_MS,
                maxReadNanos = deadlineNanos,
            ) { timeouts += it }
        }

        assertTrue("timeouts $timeouts", timeouts.size > 2)
        assertTrue("first read may use the whole deadline: $timeouts", timeouts.first() <= DEADLINE_MS)
        assertTrue("timeouts only shrink: $timeouts", timeouts.zipWithNext().all { (a, b) -> b <= a })
        assertTrue("the last read has far less than the cap left: $timeouts", timeouts.last() < READ_CAP_MS / 2)
    }

    @Test
    fun `a drip that outlasts the deadline is cut off at the deadline not a full read timeout later`() {
        val startedAt = System.nanoTime()

        assertFails<IOException> {
            RequestReader.read(
                DripInput("GET / HTTP/1.1\r\n\r\n".toByteArray(), DRIP_DELAY_MS),
                maxReadTimeoutMs = READ_CAP_MS,
                maxReadNanos = TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS),
            ) { }
        }

        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertTrue("took $elapsedMs ms", elapsedMs < DEADLINE_MS + 2 * DRIP_DELAY_MS)
    }

    @Test
    fun `a body is read under the same deadline`() {
        val timeouts = ArrayList<Int>()
        val text = "POST /login HTTP/1.1\r\nContent-Length: 8\r\n\r\npin=4821"

        val parsed = RequestReader.read(request(text), maxReadTimeoutMs = READ_CAP_MS) { timeouts += it }

        assertEquals("pin=4821", parsed.body.toString(Charsets.ISO_8859_1))
        assertTrue(timeouts.size > text.length - 8)
    }

    @Test
    fun `a Host is an IP literal with an optional port`() {
        assertTrue(listOf("192.168.0.20", "10.0.0.1:8080", "127.0.0.1:1", "[::1]", "[fe80::1]:8080").all(::isIpLiteralHost))
        val rejected = listOf(
            "", "localhost", "localhost:8080", "example.com", "1.2.3", "1.2.3.4.5", "256.1.1.1", "01.2.3.4x",
            "1.2.3.4:", "1.2.3.4:123456", "[]", "[evil]", "[1.2.3.4]", "1.2.3.4 ", " 1.2.3.4", "1.2.3.4/x", "1.2.3.4@evil.example",
        )
        assertTrue(rejected.none(::isIpLiteralHost))
    }

    @Test
    fun `an Origin must be this very host`() {
        fun fromOwnHost(vararg headers: Pair<String, String>) =
            HttpRequest("POST", "/login", headers.associate { it.first to it.second }, ByteArray(0)).isFromOwnHost()

        assertTrue(fromOwnHost("host" to "192.168.0.20:8080"))
        assertTrue(fromOwnHost("host" to "192.168.0.20:8080", "origin" to "http://192.168.0.20:8080"))
        assertFalse(fromOwnHost("host" to "192.168.0.20:8080", "origin" to "http://192.168.0.20"))
        assertFalse(fromOwnHost("host" to "192.168.0.20", "origin" to "http://evil.example"))
        assertFalse(fromOwnHost("host" to "192.168.0.20", "origin" to "null"))
        assertFalse(fromOwnHost("origin" to "http://192.168.0.20"))
        assertFalse(fromOwnHost("host" to "evil.example"))
    }

    private companion object {
        const val READ_CAP_MS = 10_000
        const val DEADLINE_MS = 400L
        const val DRIP_DELAY_MS = 40L
    }
}
