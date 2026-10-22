package nl.stijnvdpol.klinedash.core.export

import java.io.OutputStream

private const val CONTENT_SECURITY_POLICY =
    "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'"

private val REASONS = mapOf(
    200 to "OK",
    303 to "See Other",
    400 to "Bad Request",
    401 to "Unauthorized",
    404 to "Not Found",
    405 to "Method Not Allowed",
    408 to "Request Timeout",
    411 to "Length Required",
    413 to "Content Too Large",
    423 to "Locked",
    431 to "Request Header Fields Too Large",
    500 to "Internal Server Error",
)

/** Writes HTTP/1.1 responses that always close the connection and carry hardening headers. */
internal class HttpResponseWriter(private val out: OutputStream) {

    fun sendHtml(status: Int, html: String, extraHeaders: List<Pair<String, String>> = emptyList()) {
        val body = html.toByteArray(Charsets.UTF_8)
        writeHead(status, "text/html; charset=utf-8", body.size.toLong(), extraHeaders)
        out.write(body)
        out.flush()
    }

    fun sendRedirect(location: String, extraHeaders: List<Pair<String, String>> = emptyList()) {
        writeHead(303, "text/plain; charset=utf-8", 0, listOf("Location" to location) + extraHeaders)
        out.flush()
    }

    /** Writes only the head; the caller then streams the body to [out] and the close ends it. */
    fun beginStream(contentType: String, contentLength: Long?, extraHeaders: List<Pair<String, String>>) {
        writeHead(200, contentType, contentLength, extraHeaders)
    }

    private fun writeHead(
        status: Int,
        contentType: String,
        contentLength: Long?,
        extraHeaders: List<Pair<String, String>>,
    ) {
        val headers = buildList {
            add("Content-Type" to contentType)
            if (contentLength != null) add("Content-Length" to contentLength.toString())
            add("Connection" to "close")
            add("Cache-Control" to "no-store")
            add("X-Content-Type-Options" to "nosniff")
            add("Referrer-Policy" to "no-referrer")
            add("Content-Security-Policy" to CONTENT_SECURITY_POLICY)
            addAll(extraHeaders)
        }
        val head = StringBuilder("HTTP/1.1 $status ${REASONS.getValue(status)}\r\n")
        for ((name, value) in headers) {
            // Header injection guard: every value is built by this module, so a break here is a bug.
            check('\r' !in value && '\n' !in value) { "illegal header value for $name" }
            head.append(name).append(": ").append(value).append("\r\n")
        }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
    }
}
