package nl.stijnvdpol.klinedash.core.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * ELM327 over Wi-Fi: plain TCP to the adapter's access point. The common clone default is
 * 192.168.0.10:35000 (some use 192.168.4.1:35000 or 10.0.0.1:35000).
 *
 * `tcpNoDelay` matters because every ELM command is a few bytes; Nagle would add ~40 ms to each.
 * `keepAlive` lets the OS eventually notice an adapter that was unplugged without a FIN.
 * The read timeout is deliberately left at 0 (infinite): timeouts are enforced by
 * [readUntilPrompt], and closing the socket is what unblocks the reader thread.
 *
 * Android caveat (handled outside this class): when the adapter's Wi-Fi has no internet, Android
 * may keep routing traffic over another network; the app layer may need to bind the process to the
 * Wi-Fi network via ConnectivityManager.
 */
class WifiTransport(
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
) : StreamTransport() {

    override val kind: TransportKind = TransportKind.WIFI

    override val description: String get() = "$host:$port"

    @Volatile private var socket: Socket? = null

    override suspend fun openStreams(): Pair<InputStream, OutputStream> {
        val s = Socket()
        socket = s
        try {
            // Connect is blocking and not cancellable, but bounded by connectTimeoutMs.
            withContext(Dispatchers.IO) {
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            }
            return s.getInputStream() to s.getOutputStream()
        } catch (e: IOException) {
            closeUnderlying()
            throw TransportException(
                "Cannot connect to $host:$port. Is the head unit connected to the Wi-Fi network of the adapter?",
                e,
            )
        }
    }

    override fun closeUnderlying() {
        val s = socket ?: return
        socket = null
        try {
            s.close()
        } catch (e: IOException) {
            // A failing close() leaves nothing to release or retry; the socket is dropped either way.
        }
    }

    companion object {
        /** Address and port most Wi-Fi ELM327 clones ship with. */
        const val DEFAULT_HOST = "192.168.0.10"
        const val DEFAULT_PORT = 35000
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
    }
}
