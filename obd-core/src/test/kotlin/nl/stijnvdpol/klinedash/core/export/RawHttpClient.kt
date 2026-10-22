package nl.stijnvdpol.klinedash.core.export

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket

/** Response read to EOF from a raw loopback socket (the server always sends Connection: close). */
class RawResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    val text: String get() = body.toString(Charsets.UTF_8)
    fun header(name: String): String? = headers[name.lowercase()]
}

/** Minimal client so tests control every byte of the request, including malformed ones. */
class RawHttpClient(private val port: Int) {

    fun get(path: String, cookie: String? = null): RawResponse =
        send("GET $path HTTP/1.1\r\nHost: localhost\r\n" + cookieLine(cookie) + "\r\n")

    fun postLogin(pin: String, cookie: String? = null): RawResponse {
        val body = "pin=$pin"
        return send(
            "POST /login HTTP/1.1\r\nHost: localhost\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.length}\r\n" +
                cookieLine(cookie) + "\r\n" + body,
        )
    }

    fun send(rawRequest: String): RawResponse {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().apply {
                write(rawRequest.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val bytes = ByteArrayOutputStream().also { socket.getInputStream().copyTo(it) }.toByteArray()
            return parse(bytes)
        }
    }

    /** Logs in with [pin] and returns the `session=<token>` cookie pair. */
    fun login(pin: String): String {
        val response = postLogin(pin)
        check(response.status == 303) { "login failed with ${response.status}" }
        return response.header("set-cookie")!!.substringBefore(';')
    }

    private fun cookieLine(cookie: String?) = if (cookie == null) "" else "Cookie: $cookie\r\n"

    private fun parse(bytes: ByteArray): RawResponse {
        val text = String(bytes, Charsets.ISO_8859_1)
        val split = text.indexOf("\r\n\r\n")
        check(split > 0) { "no response head" }
        val lines = text.substring(0, split).split("\r\n")
        val status = lines.first().split(' ')[1].toInt()
        val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        return RawResponse(status, headers, bytes.copyOfRange(split + 4, bytes.size))
    }
}
