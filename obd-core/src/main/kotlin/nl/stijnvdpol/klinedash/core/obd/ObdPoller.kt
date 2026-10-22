package nl.stijnvdpol.klinedash.core.obd

import kotlinx.coroutines.flow.StateFlow
import nl.stijnvdpol.klinedash.core.elm.ElmLink

/**
 * Polls live data over an [ElmLink] for whatever is currently demanded, with priority tiers.
 * Implemented by [ObdPollScheduler]. The Android service owns one per link; screens, warnings and
 * the trip recorder register what they need via [demand].
 *
 * Only signals that are demanded AND supported by the ECU are polled. Nothing demanded means
 * nothing is sent (apart from engine-off detection, see [PollStats.mode]).
 */
interface ObdPoller {
    /** Latest reading per signal id. Entries for signals no longer demanded may linger; check timestamps. */
    val readings: StateFlow<Map<String, Reading>>

    /** Mode 01 PIDs the ECU reported via 0100/0120/0140/0160; null until detected on this connection. */
    val supportedPids: StateFlow<Set<Int>?>

    /** Measured throughput, shown small on screen. */
    val stats: StateFlow<PollStats>

    /** Sets the signals [owner] needs (replaces its previous set). Unknown ids are ignored. */
    fun demand(owner: String, signalIds: Set<String>)

    /** Removes everything [owner] demanded. */
    fun release(owner: String)

    /** Starts polling in the poller's scope; waits for the link to be connected. No-op if running. */
    fun start()

    suspend fun stop()

    /**
     * Runs [block] with polling paused, so diagnostics (DTC read/clear, freeze frame, VIN) get the
     * bus to themselves. Polling resumes afterwards, also when [block] throws.
     */
    suspend fun <T> withPollingPaused(block: suspend (ElmLink) -> T): T
}

/** What the poller is doing, shown with [PollStats]. */
enum class PollMode {
    /** Link not connected / supported PIDs not yet known. */
    WAITING,

    /** Normal tiered polling of demanded signals. */
    ACTIVE,

    /** No RPM for a while (engine off): only a slow RPM check to save the car battery. */
    ENGINE_OFF,
}

/** Throughput of the poller and what it could not poll; reset to the defaults while [PollMode.WAITING]. */
data class PollStats(
    val mode: PollMode = PollMode.WAITING,
    /** OBD requests completed per second over the last few seconds (all signals together). */
    val requestsPerSecond: Double = 0.0,
    /** Effective update rate per signal id in Hz over the last few seconds. */
    val signalHz: Map<String, Double> = emptyMap(),
    /** Signal ids that were demanded but the ECU does not support (or kept answering NO DATA). */
    val unsupported: Set<String> = emptySet(),
)
