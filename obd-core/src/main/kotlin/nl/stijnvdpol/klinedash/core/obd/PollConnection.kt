package nl.stijnvdpol.klinedash.core.obd

import nl.stijnvdpol.klinedash.core.elm.ElmResult

/**
 * What [ObdPollScheduler] has learnt that only holds for one connection; a new connection starts
 * fresh: the supported PIDs, how the response-count digit and multi-PID requests have fared, the
 * NO DATA bookkeeping, the engine-off detection and the rates.
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

    /** Nothing was polled until [nowMs], so there is no evidence about the engine: its clock starts over. */
    fun restartEngineEvidence(nowMs: Long) {
        rpmAliveAtMs = nowMs
    }

    /** Back to normal polling without an RPM answer, when nothing is left that could see the engine start. */
    fun resumeActive() {
        mode = PollMode.ACTIVE
    }
}

/**
 * The response-count digit appended to OBD commands ("010C" -> "010C1") on one connection. On
 * K-line the ELM327 otherwise waits out its bus timeout after the last reply to see if another ECU
 * answers, which costs a good part of every request. Some clones answer "?" or garbage to the
 * longer command, others hang until the timeout; any reply other than data or NO DATA on the first
 * try therefore drops the digit for the rest of this connection and repeats the request without
 * it. Keeping it after a timeout would make every request time out, force a reconnect and start
 * the same loop on the next connection. The digit is retried on the next connection, because a
 * reset adapter or a different one may accept it.
 */
internal class ResponseCountSuffix {
    private enum class State { UNTESTED, CONFIRMED, DISABLED }

    private var state = State.UNTESTED

    /** False until the first OBD request has shown whether the adapter takes the digit. */
    val isSettled: Boolean get() = state != State.UNTESTED

    /** Sends [command] through [transmit], with the digit unless it is disabled or [command] is an AT command. */
    suspend fun send(command: String, transmit: suspend (String) -> ElmResult): ElmResult {
        if (state == State.DISABLED || command.startsWith(AT_PREFIX, ignoreCase = true)) return transmit(command)
        val result = transmit(command + RESPONSE_COUNT_SUFFIX)
        if (state == State.UNTESTED) {
            if (result !is ElmResult.Data && result !is ElmResult.NoData) {
                state = State.DISABLED
                return transmit(command)
            }
            state = State.CONFIRMED
        }
        return result
    }

    private companion object {
        const val AT_PREFIX = "AT"
        const val RESPONSE_COUNT_SUFFIX = "1"
    }
}

/** Sliding window of event times, for the request rate and the per-signal update rates. */
internal class RateWindow(private val windowMs: Long) {
    private val requests = ArrayDeque<Long>()
    private val perSignal = HashMap<String, ArrayDeque<Long>>()

    fun recordRequest(nowMs: Long) {
        requests.addLast(nowMs)
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
