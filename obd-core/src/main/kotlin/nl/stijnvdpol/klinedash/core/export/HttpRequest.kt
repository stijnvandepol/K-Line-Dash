package nl.stijnvdpol.klinedash.core.export

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal const val MAX_HEADER_BYTES = 8 * 1024
internal const val MAX_BODY_BYTES = 1024
internal const val MAX_REQUEST_READ_NANOS = 15_000_000_000L
private const val NANOS_PER_MS = 1_000_000L

/** Shortest read timeout handed to the socket; 0 would mean "wait for ever". */
internal const val MIN_READ_TIMEOUT_MS = 50
private const val MAX_METHOD_LENGTH = 16

/** Enough digits for any length up to [MAX_BODY_BYTES] and few enough that the number fits an Int. */
private const val MAX_CONTENT_LENGTH_DIGITS = 9
private const val HEADER_TERMINATOR = "\r\n\r\n"
private const val CRLF_CRLF = 0x0D0A0D0A

/** A parsed request. Path is the raw request path (no query, no percent-decoding). */
internal class HttpRequest(
    val method: String,
    val path: String,
    private val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    /**
     * Whether the request can only have come from a page of this server itself. The Host must be an
     * IP literal (with optional port), which a DNS-rebinding page cannot produce, and an Origin
     * header, which browsers add to cross-site form posts, must be this very host. A request without
     * Origin (address bar, curl) passes.
     */
    fun isFromOwnHost(): Boolean {
        val host = header("host")
        if (host != null && !isIpLiteralHost(host)) return false
        val origin = header("origin") ?: return true
        return host != null && origin == "http://$host"
    }

    /** Value of the cookie [name] from the Cookie header, or null. */
    fun cookie(name: String): String? =
        header("cookie")?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
}

/** Thrown for a request that must be answered with an error [status] instead of being routed. */
internal class RequestRejected(val status: Int) : Exception("rejected with $status")

/** Dotted IPv4 or a bracketed IPv6 literal, then an optional port. Never resolved, only matched. */
private const val IPV4_OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"
private val IP_LITERAL_HOST = Regex(
    """^(?:$IPV4_OCTET(?:\.$IPV4_OCTET){3}|\[(?=[0-9A-Fa-f:.]*:)[0-9A-Fa-f:.]{2,45}\])(?::[0-9]{1,5})?$""",
)

internal fun isIpLiteralHost(host: String): Boolean = IP_LITERAL_HOST.matches(host)

/**
 * Minimal, strict HTTP/1.x request reader: CRLF only, no chunked bodies, bounded header and body
 * size and a total read deadline against slow-drip clients. Strictness is deliberate (request
 * smuggling needs ambiguity).
 *
 * Each read is armed with the time left until the deadline, at most the caller's maximum, through
 * the timeout callback: a client that sends a byte just before the deadline and then nothing would
 * otherwise hold a worker for the deadline plus one full read timeout.
 */
internal object RequestReader {
    /**
     * @param maxReadTimeoutMs upper bound for the timeout of a single read.
     * @param setReadTimeoutMs applies the timeout for the next read to the underlying socket.
     * @throws RequestRejected for malformed or oversized input; IOException on socket failure or when
     *   the whole request took longer than [maxReadNanos].
     */
    fun read(
        input: InputStream,
        maxReadTimeoutMs: Int,
        maxReadNanos: Long = MAX_REQUEST_READ_NANOS,
        setReadTimeoutMs: (Int) -> Unit,
    ): HttpRequest {
        val timer = ReadTimer(System.nanoTime() + maxReadNanos, maxReadTimeoutMs, setReadTimeoutMs)
        val head = readHead(input, timer)
        val lines = head.dropLast(HEADER_TERMINATOR.length).split("\r\n")
        val (method, path) = parseRequestLine(lines.first())
        val headers = parseHeaders(lines.drop(1))
        val body = if (method == "POST") readBody(input, headers, timer) else ByteArray(0)
        return HttpRequest(method, path, headers, body)
    }

    private class ReadTimer(
        private val deadline: Long,
        private val maxReadTimeoutMs: Int,
        private val setReadTimeoutMs: (Int) -> Unit,
    ) {
        /** Call before every read: fails once the deadline has passed, else limits the read to what is left. */
        fun arm() {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) throw IOException("request read took too long")
            val remainingMs = (remainingNanos / NANOS_PER_MS).coerceAtMost(maxReadTimeoutMs.toLong()).toInt()
            setReadTimeoutMs(remainingMs.coerceAtLeast(MIN_READ_TIMEOUT_MS))
        }
    }

    private fun readHead(input: InputStream, timer: ReadTimer): String {
        val buffer = ByteArrayOutputStream()
        var lastFour = 0
        while (lastFour != CRLF_CRLF) {
            if (buffer.size() >= MAX_HEADER_BYTES) throw RequestRejected(431)
            timer.arm()
            val next = input.read()
            if (next < 0) throw if (buffer.size() == 0) IOException("closed") else RequestRejected(400)
            buffer.write(next)
            lastFour = (lastFour shl 8) or next
        }
        return buffer.toString(Charsets.ISO_8859_1)
    }

    private fun parseRequestLine(line: String): Pair<String, String> {
        val parts = line.split(' ')
        if (parts.size != 3) throw RequestRejected(400)
        val (method, target, version) = parts
        val validMethod = method.length in 1..MAX_METHOD_LENGTH && method.all { it in 'A'..'Z' }
        val validTarget = target.startsWith("/") && target.all { it.code in 0x21..0x7E }
        if (!validMethod || !validTarget || version !in SUPPORTED_VERSIONS) throw RequestRejected(400)
        return method to target.substringBefore('#').substringBefore('?')
    }

    private fun parseHeaders(lines: List<String>): Map<String, String> {
        val headers = HashMap<String, String>()
        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon <= 0) throw RequestRejected(400)
            val name = line.substring(0, colon)
            if (name.any { it.code <= 0x20 || it.code >= 0x7F }) throw RequestRejected(400)
            headers[name.lowercase()] = line.substring(colon + 1).trim()
        }
        if ("transfer-encoding" in headers) throw RequestRejected(400)
        return headers
    }

    private fun readBody(input: InputStream, headers: Map<String, String>, timer: ReadTimer): ByteArray {
        val raw = headers["content-length"] ?: throw RequestRejected(411)
        if (raw.isEmpty() || raw.length > MAX_CONTENT_LENGTH_DIGITS || !raw.all { it in '0'..'9' }) throw RequestRejected(400)
        val length = raw.toInt()
        if (length > MAX_BODY_BYTES) throw RequestRejected(413)
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            timer.arm()
            val count = input.read(body, read, length - read)
            if (count < 0) throw RequestRejected(400)
            read += count
        }
        return body
    }

    private val SUPPORTED_VERSIONS = setOf("HTTP/1.0", "HTTP/1.1")
}
