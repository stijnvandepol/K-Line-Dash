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

/**
 * Tiered poller. On the target K-line car one request takes about 0.5 s (docs/research/esp32-obd2-lessons.md),
 * so a round is: every FAST command once, then one slot for the most overdue MEDIUM/SLOW command
 * ([planRound]). On CAN the commands of a round are merged into multi-PID requests ([packBatches]).
 * What is learnt about the adapter and the ECU lives in a [PollConnection] and is forgotten with it.
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
    private val rpmSignal = catalog.firstOrNull { it.id == SignalIds.RPM }
    private val pidDataLengths: Map<Int, Int> =
        catalog.mapNotNull { signal -> signal.pid?.let { pid -> signal.pidDataLength?.let { pid to it } } }.toMap()

    /** Held for every request and for the whole of [withPollingPaused]: one user of the link at a time. */
    private val linkMutex = Mutex()
    private val lifecycleLock = Any()
    private var job: Job? = null

    private class LinkLost(reason: String) : Exception(reason)

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
        val isCan = (link.state.value as? ConnectionState.Connected)?.info?.isCan == true
        val connection = PollConnection(supported, isCan, clock(), config)
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

    private suspend fun pollOnce(connection: PollConnection) {
        val snapshot = demands.value
        val commands = buildCommands(connection, snapshot)
        if (commands.isEmpty()) {
            publishStats(connection)
            waitForDemandChange(snapshot, timeoutMs = null)
            connection.restartEngineEvidence(clock())
            return
        }
        if (connection.mode == PollMode.ENGINE_OFF) return pollEngineOff(connection, commands)
        val plan = batchPlan(connection, planRound(commands, connection.lastAttemptMs, clock()))
        if (plan.isEmpty()) {
            publishStats(connection)
            waitForDemandChange(snapshot, nextDueInMs(commands, connection.lastAttemptMs, clock()))
            return
        }
        for (command in plan) {
            pollPlanned(connection, command)
            if (connection.mode != PollMode.ACTIVE) return
        }
    }

    /** With the engine off only the RPM command runs, at the check interval, until the engine starts. */
    private suspend fun pollEngineOff(connection: PollConnection, commands: List<PollCommand>) {
        val rpmCommand = commands.firstOrNull { it.command == rpmSignal?.command }
        if (rpmCommand == null) {
            connection.resumeActive()
            return
        }
        poll(connection, rpmCommand)
        if (connection.mode == PollMode.ENGINE_OFF) delay(config.engineOffCheckIntervalMs)
    }

    /**
     * On CAN the per-round commands are merged into multi-PID requests, as far as [BatchNegotiation]
     * allows. Batching waits until the response-count digit has been settled by an ordinary
     * request, so a "?" cannot be blamed on the wrong one of the two.
     */
    private fun batchPlan(connection: PollConnection, plan: List<PollCommand>): List<PollCommand> {
        if (connection.batching.isOff || !connection.suffix.isSettled) return plan
        return packBatches(plan, pidDataLengths, connection.batching.maxPids)
    }

    private fun buildCommands(connection: PollConnection, demandSnapshot: Map<String, Set<String>>): List<PollCommand> {
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

    private suspend fun poll(connection: PollConnection, command: PollCommand): ElmResult {
        val result = exchange(connection, command)
        connection.takeDeadCandidate()?.let { confirmUnsupported(connection, it) }
        publishStats(connection)
        // An instant failure (e.g. "?") must not turn the loop into a busy spin.
        if (result !is ElmResult.Data) delay(FAILED_REQUEST_PAUSE_MS)
        return result
    }

    private suspend fun pollPlanned(connection: PollConnection, command: PollCommand) {
        when {
            !command.isBatch -> poll(connection, command)
            connection.batching.isOff -> pollMembers(connection, command)
            else -> {
                val failure = pollBatch(connection, command) ?: return
                val isAnyMemberAnswered = pollMembers(connection, command)
                if (connection.batching.recordFailure(failure, isAnyMemberAnswered)) {
                    link.log.record(LogKind.INFO, "Poller: multi-PID requests switched off for this connection")
                }
            }
        }
    }

    /** The member commands one by one; true when at least one was answered with data. */
    private suspend fun pollMembers(connection: PollConnection, batch: PollCommand): Boolean {
        var isAnyAnswered = false
        for (member in batch.members) {
            if (poll(connection, member) is ElmResult.Data) isAnyAnswered = true
            if (connection.mode != PollMode.ACTIVE) break
        }
        return isAnyAnswered
    }

    /**
     * Sends [batch] and decodes every PID block of the reply. Returns null when all requested blocks
     * came back, otherwise the reply that fell short (including "?", NO DATA and a reply with a
     * block missing), for the caller to fall back to single requests.
     */
    private suspend fun pollBatch(connection: PollConnection, batch: PollCommand): ElmResult? {
        val result = request(connection, batch.command)
        val now = clock()
        connection.window.recordRequest(now)
        val requested = batch.members.mapNotNull { it.pid }.associateWith { pidDataLengths.getValue(it) }
        val blocks = (result as? ElmResult.Data)?.let { MultiPidResponse.split(it, requested) }.orEmpty()
        if (batch.members.any { member -> member.pid !in blocks }) {
            link.log.record(LogKind.INFO, "Poller: multi-PID request ${batch.command} not answered in full")
            publishStats(connection)
            return result
        }
        if (connection.batching.recordAnswered()) {
            link.log.record(LogKind.INFO, "Poller: adapter and ECU accept multi-PID requests")
        }
        applyBatch(connection, batch, blocks, now)
        publishStats(connection)
        return null
    }

    private fun applyBatch(connection: PollConnection, batch: PollCommand, blocks: Map<Int, IntArray>, nowMs: Long) {
        val decoded = batch.members.flatMap { member ->
            connection.lastAttemptMs[member.command] = nowMs
            connection.clearNoData(member.command)
            val bytes = member.pid?.let { blocks[it] } ?: return@flatMap emptyList()
            member.signals.mapNotNull { signal ->
                signal.decodePayload?.invoke(bytes)?.let { Reading(signal.id, it, nowMs) }
            }
        }
        publishReadings(connection, decoded, nowMs)
    }

    private suspend fun exchange(connection: PollConnection, command: PollCommand): ElmResult {
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
    private suspend fun confirmUnsupported(connection: PollConnection, command: PollCommand) {
        connection.clearNoData(command.command)
        val rpm = rpmSignal?.takeIf { it.isSupportedBy(connection.supported) }
        if (rpm != null) {
            val probe = PollCommand(rpm.command, PollCommand.EVERY_ROUND, listOf(rpm))
            if (exchange(connection, probe) !is ElmResult.Data) return
        }
        connection.deadCommands += command.command
    }

    /** Sends [command] with the response-count digit while the adapter takes it, see [ResponseCountSuffix]. */
    private suspend fun request(connection: PollConnection, command: String): ElmResult =
        connection.suffix.send(command, ::transmit)

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

    private fun applyResult(connection: PollConnection, command: PollCommand, result: ElmResult, nowMs: Long) {
        if (result is ElmResult.NoData) {
            applyNoData(connection, command, nowMs)
            return
        }
        connection.clearNoData(command.command)
        if (result is ElmResult.Data) applyData(connection, command, result, nowMs)
    }

    private fun applyData(connection: PollConnection, command: PollCommand, data: ElmResult.Data, nowMs: Long) {
        val decoded = command.signals.mapNotNull { signal ->
            signal.decode(data)?.let { Reading(signal.id, it, nowMs) }
        }
        publishReadings(connection, decoded, nowMs)
    }

    private fun publishReadings(connection: PollConnection, decoded: List<Reading>, nowMs: Long) {
        if (decoded.isEmpty()) return
        _readings.update { it + decoded.associateBy { reading -> reading.signalId } }
        decoded.forEach { connection.window.recordSignal(it.signalId, nowMs) }
        decoded.firstOrNull { it.signalId == SignalIds.RPM }?.let { connection.trackEngine(it.value, nowMs) }
    }

    private fun applyNoData(connection: PollConnection, command: PollCommand, nowMs: Long) {
        // RPM is the engine-off probe: NO DATA there is expected evidence, never a reason to give up on it.
        if (command.command == rpmSignal?.command) {
            connection.trackEngine(rpm = null, nowMs = nowMs)
            return
        }
        connection.recordNoData(command)
    }

    private fun publishStats(connection: PollConnection) {
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
        const val LINK_LOST_PAUSE_MS = 500L
        const val DETECT_RETRY_DELAY_MS = 5_000L
        const val FAILED_REQUEST_PAUSE_MS = 50L
    }
}
