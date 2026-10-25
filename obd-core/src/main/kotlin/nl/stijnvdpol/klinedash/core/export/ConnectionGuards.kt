package nl.stijnvdpol.klinedash.core.export

import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * True for an IPv4 address that only exists on a local network: the private ranges 10/8, 172.16/12
 * and 192.168/16 that Wi-Fi routers and hotspots hand out, and loopback. The export server only
 * talks to such peers and the app only shows such addresses, so a public or carrier address
 * (mobile data, 100.64/10) never reaches the PIN page.
 */
fun isLocalNetworkAddress(address: InetAddress): Boolean =
    address is Inet4Address && (address.isSiteLocalAddress || address.isLoopbackAddress)

/**
 * Delay before the next accept after a failed one. A failing accept that is not a shutdown (out of
 * file descriptors, a broken network interface) would otherwise spin the accept thread at full
 * speed; the delay doubles up to [maxMs] and resets after a successful accept.
 */
internal class AcceptBackoff(
    private val initialMs: Long = 50,
    private val maxMs: Long = 1_000,
) {
    private var nextMs = initialMs

    /** The pause to take after a failure; each call doubles the next one, capped at [maxMs]. */
    fun nextDelayMs(): Long {
        val delay = nextMs
        nextMs = (nextMs * 2).coerceAtMost(maxMs)
        return delay
    }

    fun reset() {
        nextMs = initialMs
    }
}

/**
 * What keeps the export alive. Only authorised use counts: a successful login, or a request that
 * carries a valid session ([authorized] covers it from start to finish, so a long download is not
 * cut off for being idle). A device that merely connects, or asks for pages without logging in,
 * must not be able to hold the export open for ever.
 */
internal class ExportActivity(private val clock: () -> Long) {
    @Volatile private var lastUseMs: Long = clock()
    private val authorizedInFlight = AtomicInteger(0)

    /** Counts the moment as use (a successful login). */
    fun touch() {
        lastUseMs = clock()
    }

    /** Runs [block] as authorised use: the export is busy while it runs and was last used when it ends. */
    fun <T> authorized(block: () -> T): T {
        authorizedInFlight.incrementAndGet()
        try {
            return block()
        } finally {
            lastUseMs = clock()
            authorizedInFlight.decrementAndGet()
        }
    }

    fun isIdleFor(timeoutMs: Long): Boolean =
        authorizedInFlight.get() == 0 && clock() - lastUseMs >= timeoutMs
}

/**
 * Remembers when bytes last went out on a connection. Java sockets have no write timeout, so a
 * client that stops reading would hold a worker forever; the server closes connections whose
 * [lastProgressMs] is too old (guidelines §4: every external call has a timeout).
 */
internal class ProgressOutputStream(
    out: OutputStream,
    private val clock: () -> Long,
) : FilterOutputStream(out) {
    @Volatile var lastProgressMs: Long = clock()
        private set

    override fun write(b: Int) {
        out.write(b)
        lastProgressMs = clock()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        // FilterOutputStream would write byte by byte; pass the whole chunk through instead.
        out.write(b, off, len)
        lastProgressMs = clock()
    }
}
