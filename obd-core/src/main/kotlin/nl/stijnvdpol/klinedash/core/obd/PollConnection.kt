package nl.stijnvdpol.klinedash.core.obd

import nl.stijnvdpol.klinedash.core.elm.ElmResult

/**
 * What [ObdPollScheduler] has learnt that only holds for one connection; a new connection starts
 * fresh: the supported PIDs, how the response-count digit and multi-PID requests have fared, the
 * NO DATA bookkeeping, the engine-off detection, the missing-ECU backoff and the rates.
 */
internal class PollConnection(
    val supported: Set<Int>,
    isCan: Boolean,
    startMs: Long,
    private val config: PollerConfig,
) {
    var mode = PollMode.ACTIVE
        private set
    val suffix = ResponseCountSuffix()
    val batching = BatchNegotiation(isCan)
    val deadCommands = HashSet<String>()
    val lastAttemptMs = HashMap<String, Long>()
    val window = RateWindow(config.statsWindowMs)

    private var rpmAliveAtMs = startMs
    private val noDataStreak = HashMap<String, Int>()

    /** Requests in a row that failed on the bus itself (init, CAN or bus error), see [recordNoEcu]. */
    private var noEcuStreak = 0

    /** A command that reached the NO DATA limit and still has to be checked against a live ECU. */
    private var deadCandidate: PollCommand? = null

    /** Counts a NO DATA answer to [command]; [PollerConfig.noDataLimit] in a row make it a dead candidate. */
    fun recordNoData(command: PollCommand) {
        val streak = (noDataStreak[command.command] ?: 0) + 1
        noDataStreak[command.command] = streak
        if (streak >= config.noDataLimit) deadCandidate = command
    }

    /** Any answer but NO DATA ends the streak of [command]. */
    fun clearNoData(command: String) {
        noDataStreak.remove(command)
    }

    /** The command waiting to be confirmed unsupported, if any; it is handed out once. */
    fun takeDeadCandidate(): PollCommand? = deadCandidate.also { deadCandidate = null }

    /**
     * [rpm] is the decoded value, or null for NO DATA. A positive value means the engine runs; none
     * for [PollerConfig.engineOffAfterMs] switches to [PollMode.ENGINE_OFF].
     */
    fun trackEngine(rpm: Double?, nowMs: Long) {
        if (rpm != null && rpm > 0) {
            rpmAliveAtMs = nowMs
            mode = PollMode.ACTIVE
        } else if (mode == PollMode.ACTIVE && nowMs - rpmAliveAtMs >= config.engineOffAfterMs) {
            mode = PollMode.ENGINE_OFF
        }
    }

    /**
     * A request failed because nothing answered on the bus (bus init failed, CAN or bus error): the
     * ignition is off or the adapter is not on the car's bus, which engine tracking does not see.
     * [PollerConfig.noEcuAfterErrors] in a row switch to [PollMode.ENGINE_OFF], whose slow RPM probe
     * then backs off (see [engineOffCheckDelayMs]) instead of repeating a seconds-long K-line init
     * several times a second.
     */
    fun recordNoEcu() {
        noEcuStreak++
        if (isEcuMissing && mode == PollMode.ACTIVE) mode = PollMode.ENGINE_OFF
    }

    /** The ECU answered (data, or NO DATA from a live bus): it is there, so the backoff starts over. */
    fun recordEcuAnswered() {
        noEcuStreak = 0
    }

    val isEcuMissing: Boolean get() = noEcuStreak >= config.noEcuAfterErrors

    /**
     * Pause between two probes in [PollMode.ENGINE_OFF]: the check interval, and while the ECU is
     * missing twice as long after each further failure, up to [PollerConfig.noEcuCheckMaxIntervalMs].
     */
    fun engineOffCheckDelayMs(): Long {
        if (!isEcuMissing) return config.engineOffCheckIntervalMs
        val doublings = (noEcuStreak - config.noEcuAfterErrors).coerceAtMost(MAX_BACKOFF_DOUBLINGS)
        return (config.engineOffCheckIntervalMs shl doublings).coerceAtMost(config.noEcuCheckMaxIntervalMs)
    }

    /** Nothing was polled until [nowMs], so there is no evidence about the engine: its clock starts over. */
    fun restartEngineEvidence(nowMs: Long) {
        rpmAliveAtMs = nowMs
    }

    /** Back to normal polling without an RPM answer, when nothing is left that could see the engine start. */
    fun resumeActive() {
        mode = PollMode.ACTIVE
    }

    private companion object {
        /** Keeps the shift in [engineOffCheckDelayMs] far from overflowing during a night of failures. */
        const val MAX_BACKOFF_DOUBLINGS = 16
    }
}

/**
 * The response-count digit appended to OBD commands ("010C" -> "010C1") on one connection. On
 * K-line the ELM327 otherwise waits out its bus timeout after the last reply to see if another ECU
 * answers, which costs a good part of every request. Some clones answer "?" or hang until the
 * timeout on the longer command; both drop the digit for the rest of this connection at once, and
 * the request is repeated without it. Keeping it after a timeout would make every request time out,
 * force a reconnect and start the same loop on the next connection.
 *
 * Any other reply that is not data or NO DATA (DATA ERROR, a garbled line, CAN or bus errors, a
 * failed bus init) says nothing about the digit: the first request after a connect often meets a
 * bus that is still settling, and dropping the digit for the whole connection on that would cost
 * every later request. Such a reply is handed back as it is and the digit is tried again with the
 * next request; only [NON_DATA_LIMIT] of them in a row (the digit is the likelier cause then) drop
 * it. The digit is retried on the next connection, because a reset adapter or a different one may
 * accept it.
 */
internal class ResponseCountSuffix {
    private enum class State { UNTESTED, CONFIRMED, DISABLED }

    private var state = State.UNTESTED

    /** Replies in a row, while untested, that were neither data nor NO DATA. */
    private var nonDataInARow = 0

    /** False until an OBD request has shown whether the adapter takes the digit. */
    val isSettled: Boolean get() = state != State.UNTESTED

    /** Sends [command] through [transmit], with the digit unless it is disabled or [command] is an AT command. */
    suspend fun send(command: String, transmit: suspend (String) -> ElmResult): ElmResult {
        if (state == State.DISABLED || command.startsWith(AT_PREFIX, ignoreCase = true)) return transmit(command)
        val result = transmit(command + RESPONSE_COUNT_SUFFIX)
        if (state != State.UNTESTED) return result
        if (result is ElmResult.Data || result is ElmResult.NoData) {
            state = State.CONFIRMED
            return result
        }
        nonDataInARow++
        val isRejected = result is ElmResult.Unknown || result is ElmResult.Timeout
        if (!isRejected && nonDataInARow < NON_DATA_LIMIT) return result
        state = State.DISABLED
        return transmit(command)
    }

    private companion object {
        const val AT_PREFIX = "AT"
        const val RESPONSE_COUNT_SUFFIX = "1"
        const val NON_DATA_LIMIT = 2
    }
}

/** Sliding window of event times, for the request rate, the per-signal update rates and the round-trip time. */
internal class RateWindow(private val windowMs: Long) {
    private val requests = ArrayDeque<Long>()
    private val perSignal = HashMap<String, ArrayDeque<Long>>()

    /** (end time, duration) of each answered request in the window. */
    private val roundTrips = ArrayDeque<Pair<Long, Long>>()

    fun recordRequest(nowMs: Long) {
        requests.addLast(nowMs)
    }

    /** One request that was answered at [nowMs] after [durationMs] on the wire. */
    fun recordRoundTrip(nowMs: Long, durationMs: Long) {
        roundTrips.addLast(nowMs to durationMs)
    }

    /** Mean round-trip time of the requests answered in the window; 0 when there were none. */
    fun meanRoundTripMs(nowMs: Long): Double {
        while (roundTrips.isNotEmpty() && roundTrips.first().first <= nowMs - windowMs) roundTrips.removeFirst()
        return if (roundTrips.isEmpty()) 0.0 else roundTrips.sumOf { it.second }.toDouble() / roundTrips.size
    }

    fun recordSignal(signalId: String, nowMs: Long) {
        perSignal.getOrPut(signalId) { ArrayDeque() }.addLast(nowMs)
    }

    fun requestsPerSecond(nowMs: Long): Double = ratePerSecond(requests, nowMs)

    fun signalHz(nowMs: Long): Map<String, Double> =
        perSignal.mapValues { (_, times) -> ratePerSecond(times, nowMs) }.filterValues { it > 0.0 }

    private fun ratePerSecond(times: ArrayDeque<Long>, nowMs: Long): Double {
        while (times.isNotEmpty() && times.first() <= nowMs - windowMs) times.removeFirst()
        return times.size * MS_PER_SECOND / windowMs
    }

    private companion object {
        const val MS_PER_SECOND = 1_000.0
    }
}
