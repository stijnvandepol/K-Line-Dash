package nl.stijnvdpol.klinedash.core.export

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout

class HttpTripExportServerTest {
    @get:Rule val timeout: Timeout = Timeout(30, TimeUnit.SECONDS)
    @get:Rule val temp = TemporaryFolder()

    private val pin = "4821"
    private var now = 1_000_000L
    private lateinit var tripFiles: List<File>
    private var server: HttpTripExportServer? = null

    // Windows cannot create "<script>.csv"; fall back to a name that still needs HTML encoding.
    private lateinit var hostileName: String
    private lateinit var hostileEncoded: String
    private lateinit var hostileSanitised: String

    @Before
    fun createFiles() {
        val hostile = try {
            temp.newFile("<script>.csv").also {
                hostileName = it.name
                hostileEncoded = "&lt;script&gt;.csv"
                hostileSanitised = "_script_.csv"
            }
        } catch (_: java.io.IOException) {
            temp.newFile("&'trip.csv").also {
                hostileName = it.name
                hostileEncoded = "&amp;&#39;trip.csv"
                hostileSanitised = "__trip.csv"
            }
        }
        tripFiles = listOf(
            temp.newFile("b-trip.csv").apply { writeText("t,rpm\n1,900\n") },
            hostile.apply { writeText("evil,row\n") },
            temp.newFile("a-trip.csv").apply { writeBytes(ByteArray(5000) { (it % 251).toByte() }) },
        )
    }

    @After
    fun stopServer() {
        server?.stop()
    }

    private fun startServer(
        maxPinAttempts: Int = 3,
        idleTimeoutMs: Long = 60_000L,
        source: TripFileSource = TripFileSource { tripFiles },
    ): RawHttpClient {
        val created = HttpTripExportServer(
            ExportServerConfig(port = 0, pin = pin, idleTimeoutMs = idleTimeoutMs, maxPinAttempts = maxPinAttempts),
            source,
            clock = { now },
        )
        server = created
        return RawHttpClient(created.start())
    }

    private fun sortedByName() = tripFiles.sortedBy { it.name }

    @Test
    fun `root without cookie shows the PIN form`() {
        val response = startServer().get("/")
        assertEquals(200, response.status)
        assertTrue(response.text.contains("action=\"/login\""))
        assertFalse(response.text.contains("download"))
        assertEquals("no-store", response.header("cache-control"))
    }

    @Test
    fun `wrong PIN is rejected without a cookie`() {
        val response = startServer().postLogin("0000")
        assertEquals(401, response.status)
        assertNull(response.header("set-cookie"))
        assertTrue(response.text.contains("Wrong PIN"))
    }

    @Test
    fun `server locks after the maximum number of wrong PINs even for the right PIN`() {
        val client = startServer(maxPinAttempts = 3)
        repeat(3) { assertEquals(401, client.postLogin("1111").status) }
        val locked = client.postLogin(pin)
        assertEquals(423, locked.status)
        assertNull(locked.header("set-cookie"))
        assertTrue(locked.text.contains("restart the export on the head unit"))
    }

    @Test
    fun `correct PIN sets a hardened session cookie and lists files with encoded names`() {
        val client = startServer()
        val login = client.postLogin(pin)
        assertEquals(303, login.status)
        assertEquals("/", login.header("location"))
        val cookie = login.header("set-cookie")!!
        assertTrue(Regex("^session=[0-9a-f]{32}; HttpOnly; SameSite=Strict; Path=/$").matches(cookie))

        val page = client.get("/", cookie.substringBefore(';'))
        assertEquals(200, page.status)
        assertTrue(page.text.contains(hostileEncoded))
        assertFalse(page.text.contains(hostileName))
        assertTrue(page.text.contains("/download/all.zip"))
        assertTrue(page.text.contains("/download/0") && page.text.contains("/download/2"))
        assertFalse(page.text.contains("/download/3"))
        // Sorted by name: the punctuation-first name < "a-trip.csv" < "b-trip.csv".
        assertTrue(page.text.indexOf(hostileEncoded) < page.text.indexOf("a-trip.csv"))
        assertTrue(page.text.indexOf("a-trip.csv") < page.text.indexOf("b-trip.csv"))
    }

    @Test
    fun `session tokens differ between logins`() {
        val client = startServer()
        assertTrue(client.login(pin) != client.login(pin))
    }

    @Test
    fun `download by index streams the exact bytes with a sanitised attachment name`() {
        val client = startServer()
        val cookie = client.login(pin)
        sortedByName().forEachIndexed { index, file ->
            val response = client.get("/download/$index", cookie)
            assertEquals(200, response.status)
            assertArrayEquals(file.readBytes(), response.body)
            assertEquals(file.length().toString(), response.header("content-length"))
            assertTrue(response.header("content-type")!!.startsWith("text/csv"))
            val disposition = response.header("content-disposition")!!
            assertTrue(disposition.startsWith("attachment; filename=\""))
            assertFalse(disposition.contains("<") || disposition.contains("/"))
        }
        val scriptIndex = sortedByName().indexOfFirst { it.name == hostileName }
        assertEquals(
            "attachment; filename=\"$hostileSanitised\"",
            client.get("/download/$scriptIndex", cookie).header("content-disposition"),
        )
    }

    @Test
    fun `a client that stops reading is let go after the write stall timeout`() {
        // Far larger than the loopback socket buffers, so the server blocks in write().
        val big = temp.newFile("big.csv").apply { writeBytes(ByteArray(64 * 1024 * 1024)) }
        val client = startServer(source = TripFileSource { listOf(big) })
        val cookie = client.login(pin)
        val running = server!!
        java.net.Socket(java.net.InetAddress.getLoopbackAddress(), running.start()).use { stalled ->
            stalled.getOutputStream().apply {
                write("GET /download/0 HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\n\r\n".toByteArray())
                flush()
            }
            awaitTrue("download started") { running.activeConnectionCount == 1 }
            Thread.sleep(STALL_SETTLE_MS) // let the server fill the buffers and block

            running.closeStalledResponses()
            assertEquals("still within the timeout", 1, running.activeConnectionCount)

            now += WRITE_STALL_TIMEOUT_MS
            running.closeStalledResponses()
            awaitTrue("stalled response closed") { running.activeConnectionCount == 0 }
        }
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for: $what" }
            Thread.sleep(POLL_MS)
        }
    }

    @Test
    fun `accept backoff doubles up to its cap and resets`() {
        val backoff = AcceptBackoff(initialMs = 50, maxMs = 300)
        assertEquals(listOf(50L, 100L, 200L, 300L, 300L), List(5) { backoff.nextDelayMs() })
        backoff.reset()
        assertEquals(50L, backoff.nextDelayMs())
    }

    @Test
    fun `download without a session redirects to the PIN page`() {
        val client = startServer()
        listOf("/download/0", "/download/all.zip").forEach { path ->
            val response = client.get(path)
            assertEquals(303, response.status)
            assertEquals("/", response.header("location"))
            assertEquals(0, response.body.size)
        }
        assertEquals(303, client.get("/download/0", "session=" + "0".repeat(32)).status)
    }

    @Test
    fun `out of range index is 404`() {
        val client = startServer()
        val cookie = client.login(pin)
        listOf("/download/3", "/download/99999", "/download/999999999999").forEach {
            assertEquals(it, 404, client.get(it, cookie).status)
        }
    }

    @Test
    fun `path traversal attempts never reach the file system`() {
        val client = startServer()
        val cookie = client.login(pin)
        val secret = temp.newFile("secret.txt").apply { writeText("do-not-leak") }
        listOf(
            "/download/../x",
            "/download/%2e%2e",
            "/download/%2e%2e/secret.txt",
            "/download/..%2fsecret.txt",
            "/download/${secret.name}",
            "/download/${secret.absolutePath.replace('\\', '/')}",
            "/download/-1",
            "/download/0/extra",
            "/../secret.txt",
        ).forEach {
            val response = client.get(it, cookie)
            assertEquals(it, 404, response.status)
            assertFalse(response.text.contains("do-not-leak"))
        }
    }

    @Test
    fun `zip contains every file with its exact content`() {
        val client = startServer()
        val cookie = client.login(pin)
        val response = client.get("/download/all.zip", cookie)
        assertEquals(200, response.status)
        assertEquals("application/zip", response.header("content-type"))
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(response.body.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
        }
        assertEquals(setOf("a-trip.csv", "b-trip.csv", hostileSanitised), entries.keys)
        tripFiles.forEach { file ->
            assertArrayEquals(file.readBytes(), entries.getValue(sanitizeDownloadName(file.name)))
        }
    }

    @Test
    fun `download re-resolves against the current list`() {
        val files = tripFiles.toMutableList()
        val client = startServer(source = TripFileSource { files.toList() })
        val cookie = client.login(pin)
        assertEquals(200, client.get("/download/2", cookie).status)
        files.removeAt(0)
        assertEquals(404, client.get("/download/2", cookie).status)
        files += temp.newFile("0-new.csv").apply { writeText("new") }
        val newIndex = files.sortedBy { it.name }.indexOfFirst { it.name == "0-new.csv" }
        assertEquals("new", client.get("/download/$newIndex", cookie).text)
    }

    @Test
    fun `methods other than GET and POST are 405`() {
        val client = startServer()
        listOf("PUT", "DELETE", "HEAD", "OPTIONS", "PATCH").forEach {
            val response = client.send("$it / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertEquals(it, 405, response.status)
            assertEquals("GET, POST", response.header("allow"))
        }
        assertEquals(404, client.get("/nothing").status)
        assertEquals(405, client.send("POST / HTTP/1.1\r\nContent-Length: 0\r\n\r\n").status)
    }

    @Test
    fun `malformed login requests are rejected without counting as attempts`() {
        val client = startServer(maxPinAttempts = 1)
        assertEquals(400, client.send("POST /login HTTP/1.1\r\nContent-Length: 8\r\n\r\npin=4821").status)
        assertEquals(400, client.postLogin("1".repeat(MAX_PIN_LENGTH + 1)).status)
        assertEquals(400, client.postLogin("%zz").status)
        val noLength = client.send("POST /login HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\n\r\n")
        assertEquals(411, noLength.status)
        val bigBody = "pin=" + "1".repeat(MAX_BODY_BYTES)
        val tooBig = client.send(
            "POST /login HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: ${bigBody.length}\r\n\r\n$bigBody",
        )
        assertEquals(413, tooBig.status)
        assertEquals(303, client.postLogin(pin).status)
    }

    @Test
    fun `oversized and malformed requests are refused`() {
        val client = startServer()
        val huge = client.send("GET / HTTP/1.1\r\nX-Filler: ${"a".repeat(MAX_HEADER_BYTES + 100)}\r\n\r\n")
        assertEquals(431, huge.status)
        assertEquals(400, client.send("GARBAGE\r\n\r\n").status)
        assertEquals(400, client.send("GET / HTTP/2.0\r\n\r\n").status)
        assertEquals(400, client.send("GET http://evil/ HTTP/1.1\r\n\r\n").status)
        assertEquals(400, client.send("GET / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n").status)
        assertEquals(200, client.get("/").status)
    }

    @Test
    fun `idle timeout stops the server and a request postpones it`() {
        val created = HttpTripExportServer(
            ExportServerConfig(port = 0, pin = pin, idleTimeoutMs = 1_000L),
            TripFileSource { tripFiles },
            clock = { now },
        )
        server = created
        val client = RawHttpClient(created.start())

        now += 900
        created.checkIdle()
        assertTrue(created.isRunning)

        assertEquals(200, client.get("/").status)
        // The client sees the response end when the socket closes; the worker only then finishes
        // its bookkeeping (last request time, active count). Advancing the clock before that would
        // race it.
        awaitTrue("request finished on the server") { created.activeConnectionCount == 0 }
        now += 900
        created.checkIdle()
        assertTrue("a request must reset the idle timer", created.isRunning)

        now += 200
        created.checkIdle()
        assertFalse(created.isRunning)
    }

    @Test
    fun `stop is idempotent frees the port and start is idempotent while running`() {
        val created = HttpTripExportServer(ExportServerConfig(port = 0, pin = pin), TripFileSource { tripFiles })
        server = created
        val port = created.start()
        assertEquals(port, created.start())
        assertTrue(created.isRunning)

        created.stop()
        created.stop()
        assertFalse(created.isRunning)
        ServerSocket(port).use { assertNotNull(it) }
    }

    @Test
    fun `an IPv6 peer gets no answer`() {
        val port = HttpTripExportServer(ExportServerConfig(port = 0, pin = pin), TripFileSource { tripFiles })
            .also { server = it }
            .start()
        // Without IPv6 on this machine the connect fails, which is the same outcome: nothing is served.
        val answer = try {
            Socket(InetAddress.getByName("::1"), port).use { socket ->
                socket.soTimeout = 10_000
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                socket.getInputStream().readBytes()
            }
        } catch (_: IOException) {
            ByteArray(0)
        }
        assertEquals("", answer.toString(Charsets.ISO_8859_1))
    }

    @Test
    fun `restarting the server clears the lockout`() {
        val created = HttpTripExportServer(
            ExportServerConfig(port = 0, pin = pin, maxPinAttempts = 1),
            TripFileSource { tripFiles },
        )
        server = created
        var client = RawHttpClient(created.start())
        client.postLogin("0000")
        assertEquals(423, client.postLogin(pin).status)
        created.stop()
        client = RawHttpClient(created.start())
        assertEquals(303, client.postLogin(pin).status)
    }

    @Test
    fun `responses carry hardening headers`() {
        val response = startServer().get("/")
        assertEquals("nosniff", response.header("x-content-type-options"))
        assertEquals("close", response.header("connection"))
        assertTrue(response.header("content-security-policy")!!.contains("default-src 'none'"))
    }

    private companion object {
        const val STALL_SETTLE_MS = 500L
        const val AWAIT_SECONDS = 10L
        const val POLL_MS = 20L
    }
}
