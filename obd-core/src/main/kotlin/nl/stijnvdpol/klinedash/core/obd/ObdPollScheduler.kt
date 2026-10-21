package nl.stijnvdpol.klinedash.core.obd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.elm.ElmLink
import nl.stijnvdpol.klinedash.core.elm.ElmResult
import nl.stijnvdpol.klinedash.core.log.LogKind
import nl.stijnvdpol.klinedash.core.transport.TransportException

/** Timing and thresholds of [ObdPollScheduler]. All durations in milliseconds. */
data class PollerConfig(
    /** A MEDIUM command is polled again once this long has passed since its last attempt. */
    val mediumIntervalMs: Long = 3_000,
    /** Same for SLOW commands. */
    val slowIntervalMs: Long = 15_000,
    /** RPM 0 (or NO DATA) for this long switches to [PollMode.ENGINE_OFF]. */
    val engineOffAfterMs: Long = 180_000,
    /** In [PollMode.ENGINE_OFF] the RPM check runs this often; it is also the RPM interval when RPM is not demanded. */
    val engineOffCheckIntervalMs: Long = 5_000,
    /** This many NO DATA answers in a row mark a command's signals unsupported for the connection. */
    val noDataLimit: Int = 3,
    /** Window for the rates in [PollStats]. */
    val statsWindowMs: Long = 5_000,
    /** Timeout per request handed to [ElmLink.execute]. */
    val requestTimeoutMs: Long = 2_000,
)

/** One command to send, with every demanded signal that is decoded from its reply. */
internal class PollCommand(
    val command: String,
    /** [EVERY_ROUND] or the minimum time between two attempts. */
    val intervalMs: Long,
    val signals: List<Signal>,
) {
    val isEveryRound: Boolean get() = intervalMs == EVERY_ROUND

    companion object {
        const val EVERY_ROUND = 0L
    }
}

internal fun intervalFor(
    signal: Signal,
    detectorOnly: Signal?,
    config: PollerConfig,
): Long = when {
    signal === detectorOnly -> config.engineOffCheckIntervalMs
    signal.defaultTier == PollTier.FAST -> PollCommand.EVERY_ROUND
    signal.defaultTier == PollTier.MEDIUM -> config.mediumIntervalMs
    else -> config.slowIntervalMs
}

/** Demanded signals the ECU can answer: supported (or adapter commands) and not given up on. */
internal fun selectPollable(
    demandedIds: Set<String>,
    catalog: List<Signal>,
    supportedPids: Set<Int>,
    deadCommands: Set<String>,
): List<Signal> = catalog.filter {
    it.id in demandedIds && it.isSupportedBy(supportedPids) && it.command !in deadCommands
}

private fun Signal.isSupportedBy(supportedPids: Set<Int>): Boolean = pid == null || pid in supportedPids

/**
 * One [PollCommand] per distinct command; its interval is the fastest of its signals. The RPM
 * [detector] joins the commands when nobody demanded it, at the engine-off check interval.
 */
internal fun buildPollCommands(pollable: List<Signal>, detector: Signal?, config: PollerConfig): List<PollCommand> {
    val detectorOnly = detector?.takeIf { d -> pollable.none { it.id == d.id } }
    val all = if (detectorOnly != null) pollable + detectorOnly else pollable
    return all.groupBy { it.command }.map { (command, signals) ->
        PollCommand(command, signals.minOf { intervalFor(it, detectorOnly, config) }, signals)
    }
}

private fun PollCommand.overdueMs(lastAttemptMs: Map<String, Long>, nowMs: Long): Long {
    val last = lastAttemptMs[command] ?: return Long.MAX_VALUE
    return nowMs - last - intervalMs
}

/** The rotating slot: the most overdue non-FAST command, or null when none is due yet. */
internal fun pickRotating(commands: List<PollCommand>, lastAttemptMs: Map<String, Long>, nowMs: Long): PollCommand? =
    commands.filter { !it.isEveryRound && it.overdueMs(lastAttemptMs, nowMs) >= 0 }
        .maxByOrNull { it.overdueMs(lastAttemptMs, nowMs) }

/** Every FAST command once, then the rotating slot if something is due. */
internal fun planRound(commands: List<PollCommand>, lastAttemptMs: Map<String, Long>, nowMs: Long): List<PollCommand> {
    val fast = commands.filter { it.isEveryRound }
    val slot = pickRotating(commands, lastAttemptMs, nowMs) ?: return fast
    return fast + slot
}

/** Time until the first non-FAST command is due; only meaningful when such a command exists. */
internal fun nextDueInMs(commands: List<PollCommand>, lastAttemptMs: Map<String, Long>, nowMs: Long): Long =
    commands.filter { !it.isEveryRound }.minOf { -it.overdueMs(lastAttemptMs, nowMs) }.coerceAtLeast(1)

/**
 * Tiered poller. On the target K-line car one request takes about 0.5 s (docs/research/esp32-obd2-lessons.md),
 * so a round is: every FAST command once, then one slot for the most overdue MEDIUM/SLOW command.
 *
 * Failure model: when the link dies ([IllegalStateException] / [TransportException] from
 * [ElmLink.execute], or the state leaving Connected) the poller drops to [PollMode.WAITING], forgets
 * the supported PIDs and starts over on the next connection. Anything else escaping a request is a bug
 * and propagates into [scope].
 */
class ObdPollScheduler(
    private val link: ElmLink,
    private val scope: CoroutineScope,
    private val catalog: List<Signal> = Signals.all,
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: PollerConfig = PollerConfig(),
) : ObdPoller {

    private val _readings = MutableStateFlow<Map<String, Reading>>(emptyMap())
    override val readings: StateFlow<Map<String, Reading>> = _readings

    private val _supportedPids = MutableStateFlow<Set<Int>?>(null)
    override val supportedPids: StateFlow<Set<Int>?> = _supportedPids

    private val _stats = MutableStateFlow(PollStats())
    override val stats: StateFlow<PollStats> = _stats

    private val demands = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val knownIds = catalog.mapTo(HashSet()) { it.id }
    private val rpmSignal = catalog.firstOrNull { it.id == RPM_ID }

    /** Held for every request and for the whole of [withPollingPaused]: one user of the link at a time. */
    private val linkMutex = Mutex()
    private val lifecycleLock = Any()
    private var job: Job? = null

    private class LinkLost(reason: String) : Exception(reason)

    /** How the response-count suffix ("010C1") has fared on this connection. */
    private enum class SuffixState { UNTESTED, CONFIRMED, DISABLED }

    /** Everything that is only valid for one connection; a new connection starts fresh. */
    private class Connection(val supported: Set<Int>, startMs: Long, statsWindowMs: Long) {
        var mode = PollMode.ACTIVE
        var suffix = SuffixState.UNTESTED
        var rpmAliveAtMs = startMs

        /** A command that reached the NO DATA limit and still has to be checked against a live ECU. */
        var deadCandidate: PollCommand? = null
        val noDataStreak = HashMap<String, Int>()
        val deadCommands = HashSet<String>()
        val lastAttemptMs = HashMap<String, Long>()
        val window = RateWindow(statsWindowMs)
    }

    override fun demand(owner: String, signalIds: Set<String>) {
        val known = signalIds.filterTo(HashSet()) { it in knownIds }
        demands.update { if (known.isEmpty()) it - owner else it + (owner to known) }
    }

    override fun release(owner: String) {
        demands.update { it - owner }
    }

    override fun start() {
        synchronized(lifecycleLock) {
            if (job?.isActive == true) return
            job = scope.launch { run() }
        }
    }

    override suspend fun stop() {
        val running = synchronized(lifecycleLock) {
            val j = job
            job = null
            j
        }
        running?.cancelAndJoin()
        resetToWaiting()
    }

    override suspend fun <T> withPollingPaused(block: suspend (ElmLink) -> T): T =
        linkMutex.withLock { block(link) }

    // ---------------------------------------------------------------- connection lifecycle

    private suspend fun run() {
        while (true) {
            link.state.first { it is ConnectionState.Connected }
            try {
                coroutineScope {
                    launch { throwWhenLinkLeavesConnected() }
                    pollConnection()
                }
            } catch (e: LinkLost) {
                link.log.record(LogKind.INFO, "Poller: link lost (${e.message}), waiting for reconnect")
            }
            resetToWaiting()
            // The manager clears its link a moment before it reports the new state; without a pause a
            // stale "Connected" would spin through failing requests.
            delay(LINK_LOST_PAUSE_MS)
        }
    }

    private suspend fun throwWhenLinkLeavesConnected() {
        val state = link.state.first { it !is ConnectionState.Connected }
        throw LinkLost("state is $state")
    }

    private fun resetToWaiting() {
        _supportedPids.value = null
        _stats.value = PollStats()
    }

    private suspend fun pollConnection(): Nothing {
        val supported = detectSupportedPids()
        _supportedPids.value = supported
        val connection = Connection(supported, clock(), config.statsWindowMs)
        publishStats(connection)
        while (true) pollOnce(connection)
    }

    private suspend fun detectSupportedPids(): Set<Int> {
        while (true) {
            try {
                return SupportedPidDetector.detect(::transmit)
            } catch (e: SupportedPidDetectionException) {
                // Typically ignition off with a live adapter: keep the link, ask again later.
                link.log.record(LogKind.ERROR, "Poller: ${e.message}")
                delay(DETECT_RETRY_DELAY_MS)
            }
        }
    }

    // ---------------------------------------------------------------- polling

    private suspend fun pollOnce(connection: Connection) {
        val snapshot = demands.value
        val commands = buildCommands(connection, snapshot)
        if (commands.isEmpty()) {
            publishStats(connection)
            waitForDemandChange(snapshot, timeoutMs = null)
            connection.rpmAliveAtMs = clock() // no RPM samples while idle, so no evidence about the engine
            return
        }
        if (connection.mode == PollMode.ENGINE_OFF) {
            poll(connection, commands.first { it.command == rpmSignal?.command })
            if (connection.mode == PollMode.ENGINE_OFF) delay(config.engineOffCheckIntervalMs)
            return
        }
        val plan = planRound(commands, connection.lastAttemptMs, clock())
        if (plan.isEmpty()) {
            publishStats(connection)
            waitForDemandChange(snapshot, nextDueInMs(commands, connection.lastAttemptMs, clock()))
            return
        }
        for (command in plan) {
            poll(connection, command)
            if (connection.mode != PollMode.ACTIVE) return
        }
    }

    private fun buildCommands(connection: Connection, demandSnapshot: Map<String, Set<String>>): List<PollCommand> {
        val demandedIds = demandSnapshot.values.flatten().toSet()
        val pollable = selectPollable(demandedIds, catalog, connection.supported, connection.deadCommands)
        if (pollable.isEmpty()) return emptyList()
        val detector = rpmSignal?.takeIf { it.isSupportedBy(connection.supported) }
        return buildPollCommands(pollable, detector, config)
    }

    private suspend fun waitForDemandChange(snapshot: Map<String, Set<String>>, timeoutMs: Long?) {
        if (timeoutMs == null) {
            demands.first { it != snapshot }
        } else {
            withTimeoutOrNull(timeoutMs) { demands.first { it != snapshot } }
        }
    }

    private suspend fun poll(connection: Connection, command: PollCommand) {
        val result = exchange(connection, command)
        connection.deadCandidate?.let {
            connection.deadCandidate = null
            confirmUnsupported(connection, it)
        }
        publishStats(connection)
        // An instant failure (e.g. "?") must not turn the loop into a busy spin.
        if (result !is ElmResult.Data) delay(FAILED_REQUEST_PAUSE_MS)
    }

    private suspend fun exchange(connection: Connection, command: PollCommand): ElmResult {
        val result = request(connection, command.command)
        val now = clock()
        connection.lastAttemptMs[command.command] = now
        connection.window.recordRequest(now)
        applyResult(connection, command, result, now)
        return result
    }

    /**
     * NO DATA on a command only means "unsupported" while the ECU is alive: with the ignition off every
     * PID answers NO DATA, and giving up on all of them would leave the dashboard empty after the engine
     * starts again. So a RPM request has to be answered first; otherwise the count starts over.
     */
    private suspend fun confirmUnsupported(connection: Connection, command: PollCommand) {
        connection.noDataStreak.remove(command.command)
        val rpm = rpmSignal?.takeIf { it.isSupportedBy(connection.supported) }
        if (rpm != null) {
            val probe = PollCommand(rpm.command, PollCommand.EVERY_ROUND, listOf(rpm))
            if (exchange(connection, probe) !is ElmResult.Data) return
        }
        connection.deadCommands += command.command
    }

    /**
     * Sends [command]; OBD commands get a response-count digit appended ("010C" -> "010C1"). On K-line
     * the ELM327 otherwise waits out its bus timeout after the last reply to see if another ECU
     * answers, which costs a good part of every request. Some clones answer "?" (or garbage) to the
     * longer command; then the digit is dropped for the rest of this connection and the request is
     * repeated without it. The digit is retried on the next connection, because a reset adapter or
     * a different one may accept it.
     */
    private suspend fun request(connection: Connection, command: String): ElmResult {
        if (connection.suffix == SuffixState.DISABLED || command.startsWith(AT_PREFIX, ignoreCase = true)) {
            return transmit(command)
        }
        val result = transmit(command + RESPONSE_COUNT_SUFFIX)
        if (connection.suffix == SuffixState.UNTESTED) {
            if (result is ElmResult.Unknown || result is ElmResult.Garbage) {
                connection.suffix = SuffixState.DISABLED
                return transmit(command)
            }
            if (result is ElmResult.Data || result is ElmResult.NoData) connection.suffix = SuffixState.CONFIRMED
        }
        return result
    }

    private suspend fun transmit(command: String): ElmResult = linkMutex.withLock {
        try {
            link.execute(command, config.requestTimeoutMs)
        } catch (e: CancellationException) {
            throw e // a subclass of IllegalStateException, so it has to be caught first
        } catch (e: IllegalStateException) {
            throw LinkLost(e.message ?: "link not connected")
        } catch (e: TransportException) {
            throw LinkLost(e.message ?: "transport error")
        }
    }

    // ---------------------------------------------------------------- results

    private fun applyResult(connection: Connection, command: PollCommand, result: ElmResult, nowMs: Long) {
        if (result is ElmResult.NoData) {
            applyNoData(connection, command, nowMs)
            return
        }
        connection.noDataStreak.remove(command.command)
        if (result is ElmResult.Data) applyData(connection, command, result, nowMs)
    }

    private fun applyData(connection: Connection, command: PollCommand, data: ElmResult.Data, nowMs: Long) {
        val decoded = command.signals.mapNotNull { signal ->
            signal.decode(data)?.let { Reading(signal.id, it, nowMs) }
        }
        if (decoded.isEmpty()) return
        _readings.update { it + decoded.associateBy { reading -> reading.signalId } }
        decoded.forEach { connection.window.recordSignal(it.signalId, nowMs) }
        decoded.firstOrNull { it.signalId == RPM_ID }?.let { trackEngine(connection, it.value, nowMs) }
    }

    private fun applyNoData(connection: Connection, command: PollCommand, nowMs: Long) {
        // RPM is the engine-off probe: NO DATA there is expected evidence, never a reason to give up on it.
        if (command.command == rpmSignal?.command) {
            trackEngine(connection, rpm = null, nowMs = nowMs)
            return
        }
        val streak = (connection.noDataStreak[command.command] ?: 0) + 1
        connection.noDataStreak[command.command] = streak
        if (streak >= config.noDataLimit) connection.deadCandidate = command
    }

    /** [rpm] is the decoded value, or null for NO DATA. */
    private fun trackEngine(connection: Connection, rpm: Double?, nowMs: Long) {
        if (rpm != null && rpm > 0) {
            connection.rpmAliveAtMs = nowMs
            connection.mode = PollMode.ACTIVE
        } else if (connection.mode == PollMode.ACTIVE && nowMs - connection.rpmAliveAtMs >= config.engineOffAfterMs) {
            connection.mode = PollMode.ENGINE_OFF
        }
    }

    private fun publishStats(connection: Connection) {
        val now = clock()
        val demandedIds = demands.value.values.flatten().toSet()
        val unsupported = catalog.filter {
            it.id in demandedIds && (!it.isSupportedBy(connection.supported) || it.command in connection.deadCommands)
        }.mapTo(LinkedHashSet()) { it.id }
        _stats.value = PollStats(
            mode = connection.mode,
            requestsPerSecond = connection.window.requestsPerSecond(now),
            signalHz = connection.window.signalHz(now),
            unsupported = unsupported,
        )
    }

    private companion object {
        const val RPM_ID = "rpm"
        const val AT_PREFIX = "AT"
        const val RESPONSE_COUNT_SUFFIX = "1"
        const val LINK_LOST_PAUSE_MS = 500L
        const val DETECT_RETRY_DELAY_MS = 5_000L
        const val FAILED_REQUEST_PAUSE_MS = 50L
    }
}

/** Sliding window of event times, for the request rate and the per-signal update rates. */
private class RateWindow(private val windowMs: Long) {
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
