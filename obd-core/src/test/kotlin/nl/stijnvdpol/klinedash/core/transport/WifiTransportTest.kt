package nl.stijnvdpol.klinedash.core.transport

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Real-time integration tests against a loopback ServerSocket pretending to be an ELM327 over
 * TCP: the point is the real socket and reader thread, so no virtual time here. Every read has its
 * own timeout, and the rule below stops a broken transport from hanging the build.
 */
class WifiTransportTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(TEST_TIMEOUT_S)

    /** One-shot fake adapter: accepts a single client and hands the socket to [handler]. */
    private class FakeElm(handler: (Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                try {
                    server.accept().use { handler(it) }
                } catch (e: IOException) {
                    // server closed by the test
                }
            }
        }

        override fun close() = server.close()
    }

    private fun Socket.awaitRequest() {
        getInputStream().read(ByteArray(64))
    }

    private fun Socket.send(text: String) {
        getOutputStream().apply {
            write(text.toByteArray(Charsets.US_ASCII))
            flush()
        }
    }

    @Test
    fun reply_split_over_several_tcp_writes_is_reassembled() {
        FakeElm { s ->
            s.awaitRequest()
            for (part in listOf("ELM3", "27 v1", ".5\r\r", ">")) {
                s.send(part)
                Thread.sleep(40)
            }
            Thread.sleep(300)
        }.use { fake ->
            runBlocking {
                val t = WifiTransport("127.0.0.1", fake.port, 2000)
                try {
                    t.connect()
                    assertTrue(t.isConnected)
                    t.write("ATZ\r")
                    assertEquals("ELM327 v1.5\r\r", t.readUntilPrompt(3000))
                } finally {
                    t.close()
                }
            }
        }
    }

    @Test
    fun two_prompts_back_to_back_yield_two_responses() {
        FakeElm { s ->
            s.awaitRequest()
            s.send("OK\r\r>41 0C 1A F8\r\r>")
            Thread.sleep(300)
        }.use { fake ->
            runBlocking {
                val t = WifiTransport("127.0.0.1", fake.port, 2000)
                try {
                    t.connect()
                    t.write("ATE0\r")
                    assertEquals("OK\r\r", t.readUntilPrompt(3000))
                    assertEquals("41 0C 1A F8\r\r", t.readUntilPrompt(3000))
                } finally {
                    t.close()
                }
            }
        }
    }

    @Test
    fun timeout_carries_partial_text() {
        FakeElm { s ->
            s.awaitRequest()
            s.send("SEARCHING...")
            Thread.sleep(800)
        }.use { fake ->
            runBlocking {
                val t = WifiTransport("127.0.0.1", fake.port, 2000)
                try {
                    t.connect()
                    t.write("0100\r")
                    try {
                        t.readUntilPrompt(300)
                        fail("expected timeout")
                    } catch (e: TransportTimeoutException) {
                        assertEquals("SEARCHING...", e.partial)
                    }
                    // A timeout alone must not tear the link down.
                    assertTrue(t.isConnected)
                } finally {
                    t.close()
                }
            }
        }
    }

    @Test
    fun server_closing_connection_fails_waiting_read_fast() {
        FakeElm { s ->
            s.awaitRequest()
            // handler returns -> socket closed
        }.use { fake ->
            runBlocking {
                val t = WifiTransport("127.0.0.1", fake.port, 2000)
                try {
                    t.connect()
                    t.write("0100\r")
                    val started = System.nanoTime()
                    try {
                        t.readUntilPrompt(5000)
                        fail("expected TransportException")
                    } catch (e: TransportTimeoutException) {
                        fail("should fail fast with a plain TransportException, not time out")
                    } catch (e: TransportException) {
                        // expected
                    }
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    assertTrue("took ${elapsedMs}ms", elapsedMs < 3000)
                    assertFalse(t.isConnected)
                } finally {
                    t.close()
                }
            }
        }
    }

    @Test
    fun discardPending_drops_stale_reply() {
        FakeElm { s ->
            s.awaitRequest()
            s.send("STALE\r\r>")
            s.awaitRequest()
            s.send("FRESH\r\r>")
            Thread.sleep(300)
        }.use { fake ->
            runBlocking {
                val t = WifiTransport("127.0.0.1", fake.port, 2000)
                try {
                    t.connect()
                    t.write("A\r")
                    Thread.sleep(200) // let the stale reply arrive
                    t.discardPending()
                    t.write("B\r")
                    assertEquals("FRESH\r\r", t.readUntilPrompt(3000))
                } finally {
                    t.close()
                }
            }
        }
    }

    @Test
    fun connection_refused_becomes_TransportException() {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        runBlocking {
            val t = WifiTransport("127.0.0.1", closedPort, 1000)
            try {
                t.connect()
                fail("expected TransportException")
            } catch (e: TransportException) {
                assertFalse(t.isConnected)
            }
        }
    }

    @Test
    fun description_is_host_and_port_and_kind_is_wifi() {
        val t = WifiTransport()
        assertEquals("192.168.0.10:35000", t.description)
        assertEquals(TransportKind.WIFI, t.kind)
    }

    private companion object {
        const val TEST_TIMEOUT_S = 15L
    }
}
