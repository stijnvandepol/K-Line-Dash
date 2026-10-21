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
    private val host: String = "192.168.0.10",
    private val port: Int = 35000,
    private val connectTimeoutMs: Int = 5000,
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
                "Kan geen verbinding maken met $host:$port. Zit de tablet/head unit op het Wi-Fi van de adapter?",
                e,
            )
        }
    }

    override fun closeUnderlying() {
        val s = socket ?: return
        socket = null
        try {
            s.close()
        } catch (ignored: IOException) {
            // nothing useful to do
        }
    }
}
