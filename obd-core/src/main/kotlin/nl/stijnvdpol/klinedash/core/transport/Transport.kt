package nl.stijnvdpol.klinedash.core.transport

/** How the ELM327 adapter is physically reached. */
enum class TransportKind { BT_CLASSIC, BLE, WIFI, SIMULATOR }

/**
 * Byte pipe to an ELM327-compatible adapter. Implementations know nothing about
 * AT commands or OBD; they only move ASCII and find the '>' prompt.
 *
 * Not thread-safe: the ELM layer serialises all calls (one command in flight).
 */
interface Transport {
    val kind: TransportKind

    /** Human-readable target, e.g. "OBDII (00:1D:A5:68:98:8B)" or "192.168.0.10:35000". */
    val description: String

    val isConnected: Boolean

    /** Opens the link. Throws [TransportException] on failure. Idempotent when already connected. */
    suspend fun connect()

    /** Sends [data] as ASCII exactly as given; the caller appends the CR terminator. */
    suspend fun write(data: String)

    /**
     * Suspends until the adapter sends its '>' prompt and returns everything received
     * before it (prompt excluded, NUL bytes stripped, CR/LF kept as-is).
     * Throws [TransportTimeoutException] carrying the partial text when [timeoutMs] elapses,
     * and [TransportException] when the link drops.
     */
    suspend fun readUntilPrompt(timeoutMs: Long): String

    /** Drops anything already received but not yet consumed (late replies, clone garbage). */
    fun discardPending()

    /** Closes the link. Never throws. */
    suspend fun close()
}

open class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

class TransportTimeoutException(val partial: String, timeoutMs: Long) :
    TransportException("No prompt within ${timeoutMs}ms (partial: \"${partial.take(80)}\")")
