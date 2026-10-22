package nl.stijnvdpol.klinedash.core.export

import java.io.FilterOutputStream
import java.io.OutputStream

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
