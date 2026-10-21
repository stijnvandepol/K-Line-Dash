package nl.stijnvdpol.klinedash.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Base class for transports backed by a blocking [InputStream]/[OutputStream] pair
 * (Bluetooth Classic RFCOMM, Wi-Fi TCP).
 *
 * A dedicated reader coroutine on [Dispatchers.IO] pulls bytes off the stream, frames them
 * with [PromptBuffer] and queues each completed response in an unbounded channel, so
 * [readUntilPrompt] is just a timed receive and late replies are never lost between commands.
 *
 * IMPORTANT: `InputStream.read` is blocking and NOT cancellable. Cancelling the reader coroutine
 * does nothing until the read returns; what actually unblocks it is closing the underlying
 * socket. That is why [close] calls [closeUnderlying] first and only then cancels the scope.
 *
 * Failure model: when the reader sees EOF (-1) or an [IOException] the transport is marked
 * disconnected and the channel is closed with a [TransportException]. A caller blocked in
 * [readUntilPrompt] therefore fails immediately instead of waiting for its timeout, while
 * responses that were already queued are still delivered first.
 */
abstract class StreamTransport : Transport {

    /** Guards [promptBuffer] plus the connection fields below, which reader and callers share. */
    private val lock = Any()
    private val promptBuffer = PromptBuffer()
    private val connectMutex = Mutex()
    private val writeMutex = Mutex()

    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var channel: Channel<String>? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var connected = false

    override val isConnected: Boolean get() = connected

    /**
     * Opens the physical link and returns its streams. Throw [TransportException] with a
     * user-presentable message on failure. [SecurityException] is passed through unchanged
     * (the connection manager treats it as non-retryable); other exceptions are wrapped.
     * May be called again after a failure or [close], so it must create fresh resources.
     */
    protected abstract suspend fun openStreams(): Pair<InputStream, OutputStream>

    /**
     * Closes the socket. Must be idempotent, must not throw, and must unblock a thread that is
     * blocked in `InputStream.read` (closing a socket does that). Also called after a failed
     * [openStreams] to release a half-open socket, and from the reader thread on link loss.
     */
    protected abstract fun closeUnderlying()

    override suspend fun connect() {
        connectMutex.withLock {
            if (connected) return
            // Clean up whatever a previous (failed or dropped) session left behind.
            withContext(Dispatchers.IO) { shutdown() }

            val streams = try {
                openStreams()
            } catch (e: CancellationException) {
                releaseAfterFailedOpen()
                throw e
            } catch (e: SecurityException) {
                releaseAfterFailedOpen()
                throw e
            } catch (e: TransportException) {
                releaseAfterFailedOpen()
                throw e
            } catch (e: Exception) {
                releaseAfterFailedOpen()
                throw TransportException(e.message ?: "Verbinden met de adapter is mislukt", e)
            }

            val ch = Channel<String>(Channel.UNLIMITED)
            val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            synchronized(lock) {
                promptBuffer.clear()
                channel = ch
                scope = readerScope
                output = streams.second
                connected = true
            }
            readerScope.launch { readLoop(streams.first, ch) }
        }
    }

    override suspend fun write(data: String) {
        val out = output
        if (!connected || out == null) throw TransportException("Niet verbonden met de adapter")
        val bytes = data.toByteArray(Charsets.US_ASCII)
        try {
            withContext(Dispatchers.IO) {
                writeMutex.withLock {
                    out.write(bytes)
                    out.flush()
                }
            }
        } catch (e: IOException) {
            channel?.let { onLinkLost(it, e) }
            throw TransportException("Schrijven naar de adapter mislukt: ${e.message}", e)
        }
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        val ch = channel ?: throw TransportException("Niet verbonden met de adapter")
        // A channel closed with a cause rethrows that TransportException straight from receive().
        return try {
            withTimeout(timeoutMs) { ch.receive() }
        } catch (e: TimeoutCancellationException) {
            val partial = synchronized(lock) { promptBuffer.pending() }
            throw TransportTimeoutException(partial, timeoutMs)
        } catch (e: ClosedReceiveChannelException) {
            // Only reachable if somebody closed the channel without a cause; we always pass one.
            throw TransportException("Verbinding met de adapter is gesloten", e)
        }
    }

    override fun discardPending() {
        val ch = channel ?: return
        synchronized(lock) {
            // Holding the lock keeps the reader from queueing a response between drain and clear.
            while (ch.tryReceive().isSuccess) { /* drop */ }
            promptBuffer.clear()
        }
    }

    override suspend fun close() {
        // NonCancellable: closing must complete even when the calling coroutine is being cancelled.
        withContext(NonCancellable + Dispatchers.IO) { shutdown() }
    }

    /** Blocking teardown; safe to call repeatedly. */
    private fun shutdown() {
        val (ch, readerScope) = synchronized(lock) {
            connected = false
            output = null
            val pair = channel to scope
            scope = null
            pair
        }
        ch?.close(TransportException("Verbinding met de adapter is gesloten"))
        // Close the socket BEFORE cancelling: that is what unblocks the reader's read().
        closeUnderlyingGuarded()
        readerScope?.cancel()
    }

    private suspend fun releaseAfterFailedOpen() {
        withContext(NonCancellable + Dispatchers.IO) { closeUnderlyingGuarded() }
    }

    /**
     * [closeUnderlying] must not throw by contract. This guard only stops a subclass that breaks
     * that contract from also breaking [close] (documented as never throwing) and the teardown
     * after it; the socket is discarded either way, so there is nothing left to recover.
     */
    private fun closeUnderlyingGuarded() {
        try {
            closeUnderlying()
        } catch (e: Exception) {
            // Intentionally ignored, see above. Errors (OOM etc.) still propagate.
        }
    }

    /** Runs on a [Dispatchers.IO] thread for the lifetime of the connection. */
    private fun readLoop(input: InputStream, ch: Channel<String>) {
        val buf = ByteArray(READ_BUFFER_SIZE)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) {
                    onLinkLost(ch, null)
                    return
                }
                if (n == 0) continue
                synchronized(lock) {
                    if (channel !== ch) return // superseded by a newer connection
                    for (response in promptBuffer.feed(buf, n)) ch.trySend(response)
                }
            }
        } catch (e: Exception) {
            // IOException (socket closed / reset) is the normal case; anything else is also fatal.
            onLinkLost(ch, e)
        }
    }

    private fun onLinkLost(ch: Channel<String>, cause: Throwable?) {
        val first = synchronized(lock) {
            if (channel !== ch) return
            val wasConnected = connected
            connected = false
            if (wasConnected) {
                ch.close(TransportException("Verbinding met de adapter is verbroken", cause))
            }
            wasConnected
        }
        // Free the socket right away; a later connect() starts from a clean slate anyway.
        if (first) closeUnderlyingGuarded()
    }

    private companion object {
        const val READ_BUFFER_SIZE = 1024
    }
}
