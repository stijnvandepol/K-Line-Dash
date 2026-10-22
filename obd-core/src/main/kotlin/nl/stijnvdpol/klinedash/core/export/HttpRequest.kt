package nl.stijnvdpol.klinedash.core.export

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal const val MAX_HEADER_BYTES = 8 * 1024
internal const val MAX_BODY_BYTES = 1024
private const val MAX_REQUEST_READ_NANOS = 15_000_000_000L
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

    /** Value of the cookie [name] from the Cookie header, or null. */
    fun cookie(name: String): String? =
        header("cookie")?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
}

/** Thrown for a request that must be answered with an error [status] instead of being routed. */
internal class RequestRejected(val status: Int) : Exception("rejected with $status")

/**
 * Minimal, strict HTTP/1.x request reader: CRLF only, no chunked bodies, bounded header and body
 * size and a total read deadline against slow-drip clients. Strictness is deliberate (request
 * smuggling needs ambiguity).
 */
internal object RequestReader {
    /** @throws RequestRejected for malformed or oversized input; IOException on socket failure. */
    fun read(input: InputStream): HttpRequest {
        val deadline = System.nanoTime() + MAX_REQUEST_READ_NANOS
        val head = readHead(input, deadline)
        val lines = head.dropLast(HEADER_TERMINATOR.length).split("\r\n")
        val (method, path) = parseRequestLine(lines.first())
        val headers = parseHeaders(lines.drop(1))
        val body = if (method == "POST") readBody(input, headers, deadline) else ByteArray(0)
        return HttpRequest(method, path, headers, body)
    }

    private fun readHead(input: InputStream, deadline: Long): String {
        val buffer = ByteArrayOutputStream()
        var lastFour = 0
        while (lastFour != CRLF_CRLF) {
            if (buffer.size() >= MAX_HEADER_BYTES) throw RequestRejected(431)
            val next = input.read()
            if (next < 0) throw if (buffer.size() == 0) IOException("closed") else RequestRejected(400)
            buffer.write(next)
            lastFour = (lastFour shl 8) or next
            checkDeadline(deadline)
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

    private fun readBody(input: InputStream, headers: Map<String, String>, deadline: Long): ByteArray {
        val raw = headers["content-length"] ?: throw RequestRejected(411)
        if (raw.isEmpty() || raw.length > MAX_CONTENT_LENGTH_DIGITS || !raw.all { it in '0'..'9' }) throw RequestRejected(400)
        val length = raw.toInt()
        if (length > MAX_BODY_BYTES) throw RequestRejected(413)
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) throw RequestRejected(400)
            read += count
            checkDeadline(deadline)
        }
        return body
    }

    private fun checkDeadline(deadline: Long) {
        if (System.nanoTime() - deadline > 0) throw IOException("request read took too long")
    }

    private val SUPPORTED_VERSIONS = setOf("HTTP/1.0", "HTTP/1.1")
}
