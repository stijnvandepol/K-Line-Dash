package nl.stijnvdpol.klinedash.core.elm

import kotlinx.coroutines.flow.StateFlow
import nl.stijnvdpol.klinedash.core.log.CommLog

/**
 * The one object the rest of the app talks to: owns the transport, runs the ELM327 init,
 * keeps the link alive with automatic reconnect + backoff, and serialises commands.
 *
 * Implemented by [ElmConnectionManager] in obd-core. The Android foreground service holds one
 * instance; UI and (later) the OBD poller only use this interface.
 */
interface ElmLink {
    val state: StateFlow<ConnectionState>
    val log: CommLog

    /** Starts connecting (and reconnecting forever with backoff) in the manager's scope. No-op if running. */
    fun start()

    /** Stops reconnecting, sends nothing further and closes the transport. */
    suspend fun stop()

    /**
     * Sends one command (without CR) and returns the classified result. Suspends while another
     * command is in flight. Returns [ElmResult.Timeout] rather than throwing when the adapter is slow.
     * Throws [IllegalStateException] when not [ConnectionState.Connected].
     */
    suspend fun execute(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ElmResult

    /** Forget the saved protocol for the current adapter; next connect runs ATSP0 detection. */
    suspend fun forgetSavedProtocol()

    companion object {
        /** K-line OBD replies can take ~300 ms; first command after init can take seconds. */
        const val DEFAULT_TIMEOUT_MS = 2_000L
    }
}
