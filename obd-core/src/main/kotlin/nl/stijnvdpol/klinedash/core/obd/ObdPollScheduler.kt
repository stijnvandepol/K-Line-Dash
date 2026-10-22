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
 * One command to send, with every demanded signal that is decoded from its reply. A batch is a
 * single multi-PID request ("010C0D") standing for several ordinary commands, its [members].
 */
internal class PollCommand(
    val command: String,
    /** [EVERY_ROUND] or the minimum time between two attempts. */
    val intervalMs: Long,
    val signals: List<Signal>,
    val members: List<PollCommand> = emptyList(),
) {
    val isEveryRound: Boolean get() = intervalMs == EVERY_ROUND

    val isBatch: Boolean get() = members.isNotEmpty()

    /** The Mode 01 PID this ordinary command reads; null for adapter commands and for batches. */
    val pid: Int? get() = if (isBatch) null else signals.firstOrNull()?.pid

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

private const val MODE_CURRENT_DATA_PREFIX = "01"

/** A single CAN frame holds 7 data bytes; the response service byte "41" takes one. */
private const val SINGLE_FRAME_BLOCK_BYTES = 6

/** Bytes this command adds to a multi-PID reply ("pid" plus its data), null if it cannot be batched. */
private fun PollCommand.blockBytes(dataLengths: Map<Int, Int>): Int? {
    val pid = pid ?: return null
    if (!isEveryRound || signals.any { it.pid != pid || it.decodePayload == null }) return null
    return dataLengths[pid]?.plus(1)
}

private class Bin {
    val commands = ArrayList<PollCommand>()
    var bytes = 0
}

/**
 * Merges the per-round Mode 01 commands of [commands] into multi-PID requests of at most [maxPids]
 * PIDs, first-fit decreasing, each filled only up to what one CAN frame can carry ("41" plus
 * [SINGLE_FRAME_BLOCK_BYTES] bytes): a single-frame answer needs no flow control and no
 * multi-frame reassembly on the adapter, which is what the cheap clones are worst at
 * (docs/research/popular-obd-adapters.md, section 4). Commands that cannot be batched (slow tiers,
 * adapter commands, PIDs of unknown length) and bins that end up with one PID stay as they are.
 */
internal fun packBatches(commands: List<PollCommand>, dataLengths: Map<Int, Int>, maxPids: Int): List<PollCommand> {
    val batchable = commands.filter { it.blockBytes(dataLengths) != null }
    if (batchable.size < 2) return commands
    val bins = ArrayList<Bin>()
    for (command in batchable.sortedByDescending { it.blockBytes(dataLengths) }) {
        val size = command.blockBytes(dataLengths) ?: continue
        val bin = bins.firstOrNull { it.commands.size < maxPids && it.bytes + size <= SINGLE_FRAME_BLOCK_BYTES }
            ?: Bin().also { bins.add(it) }
        bin.commands.add(command)
        bin.bytes += size
    }
    val packed = bins.map { if (it.commands.size == 1) it.commands.first() else batchOf(it.commands) }
    return packed + commands.filter { it.blockBytes(dataLengths) == null }
}

private fun batchOf(members: List<PollCommand>): PollCommand = PollCommand(
    command = MODE_CURRENT_DATA_PREFIX + members.joinToString("") { "%02X".format(it.pid) },
    intervalMs = PollCommand.EVERY_ROUND,
    signals = members.flatMap { it.signals },
    members = members,
)

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
    private val pidDataLengths: Map<Int, Int> =
        catalog.mapNotNull { signal -> signal.pid?.let { pid -> signal.pidDataLength?.let { pid to it } } }.toMap()

    /** Held for every request and for the whole of [withPollingPaused]: one user of the link at a time. */
    private val linkMutex = Mutex()
    private val lifecycleLock = Any()
    private var job: Job? = null

    private class LinkLost(reason: String) : Exception(reason)

    /** How the response-count suffix ("010C1") has fared on this connection. */
    private enum class SuffixState { UNTESTED, CONFIRMED, DISABLED }

    /** How multi-PID requests have fared on this connection; they are never tried off CAN. */
    private enum class BatchState { UNTESTED, ON, OFF }

    /** Everything that is only valid for one connection; a new connection starts fresh. */
    private class Connection(val supported: Set<Int>, isCan: Boolean, startMs: Long, statsWindowMs: Long) {
        var mode = PollMode.ACTIVE
        var suffix = SuffixState.UNTESTED
        var batch = if (isCan) BatchState.UNTESTED else BatchState.OFF

        /** Batch requests that failed in a row while single requests still worked. */
        var batchFailures = 0
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
        val isCan = (link.state.value as? ConnectionState.Connected)?.info?.isCan == true
        val connection = Connection(supported, isCan, clock(), config.statsWindowMs)
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
            val rpmCommand = commands.firstOrNull { it.command == rpmSignal?.command }
            if (rpmCommand == null) {
                // Without an RPM command there is no way to see the engine start again.
                connection.mode = PollMode.ACTIVE
                return
            }
            poll(connection, rpmCommand)
            if (connection.mode == PollMode.ENGINE_OFF) delay(config.engineOffCheckIntervalMs)
            return
        }
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

    /**
     * On CAN the per-round commands are merged into multi-PID requests. The first batch of a
     * connection doubles as the probe and is limited to [PROBE_BATCH_PIDS] PIDs. Batching waits
     * until the response-count digit has been settled by an ordinary request, so a "?" cannot be
     * blamed on the wrong one of the two.
     */
    private fun batchPlan(connection: Connection, plan: List<PollCommand>): List<PollCommand> {
        if (connection.batch == BatchState.OFF || connection.suffix == SuffixState.UNTESTED) return plan
        val maxPids = if (connection.batch == BatchState.UNTESTED) PROBE_BATCH_PIDS else MAX_BATCH_PIDS
        return packBatches(plan, pidDataLengths, maxPids)
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

    private suspend fun poll(connection: Connection, command: PollCommand): ElmResult {
        val result = exchange(connection, command)
        connection.deadCandidate?.let {
            connection.deadCandidate = null
            confirmUnsupported(connection, it)
        }
        publishStats(connection)
        // An instant failure (e.g. "?") must not turn the loop into a busy spin.
        if (result !is ElmResult.Data) delay(FAILED_REQUEST_PAUSE_MS)
        return result
    }

    private suspend fun pollPlanned(connection: Connection, command: PollCommand) {
        when {
            !command.isBatch -> poll(connection, command)
            connection.batch == BatchState.OFF -> pollMembers(connection, command)
            else -> {
                val failure = pollBatch(connection, command) ?: return
                val singlesAnswered = pollMembers(connection, command)
                noteBatchFailure(connection, failure, singlesAnswered)
            }
        }
    }

    /** The member commands one by one; true when at least one was answered with data. */
    private suspend fun pollMembers(connection: Connection, batch: PollCommand): Boolean {
        var answered = false
        for (member in batch.members) {
            if (poll(connection, member) is ElmResult.Data) answered = true
            if (connection.mode != PollMode.ACTIVE) break
        }
        return answered
    }

    /**
     * Sends [batch] and decodes every PID block of the reply. Returns null when all requested blocks
     * came back, otherwise the reply that fell short (including "?", NO DATA and a reply with a
     * block missing), for the caller to fall back to single requests.
     */
    private suspend fun pollBatch(connection: Connection, batch: PollCommand): ElmResult? {
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
        if (connection.batch == BatchState.UNTESTED) {
            link.log.record(LogKind.INFO, "Poller: adapter and ECU accept multi-PID requests")
        }
        connection.batch = BatchState.ON
        connection.batchFailures = 0
        applyBatch(connection, batch, blocks, now)
        publishStats(connection)
        return null
    }

    /**
     * A failed batch only counts against batching when the single requests that followed were
     * answered (otherwise the ECU is just silent, e.g. ignition off), or when the adapter said "?".
     * The probe and a "?" turn batching off at once; later, two failed rounds in a row do.
     */
    private fun noteBatchFailure(connection: Connection, failure: ElmResult, singlesAnswered: Boolean) {
        if (!singlesAnswered && failure !is ElmResult.Unknown) return
        connection.batchFailures++
        val giveUp = connection.batch == BatchState.UNTESTED ||
            failure is ElmResult.Unknown ||
            connection.batchFailures >= BATCH_FAILURE_LIMIT
        if (giveUp) {
            connection.batch = BatchState.OFF
            link.log.record(LogKind.INFO, "Poller: multi-PID requests switched off for this connection")
        }
    }

    private fun applyBatch(connection: Connection, batch: PollCommand, blocks: Map<Int, IntArray>, nowMs: Long) {
        val decoded = batch.members.flatMap { member ->
            connection.lastAttemptMs[member.command] = nowMs
            connection.noDataStreak.remove(member.command)
            val bytes = member.pid?.let { blocks[it] } ?: return@flatMap emptyList()
            member.signals.mapNotNull { signal ->
                signal.decodePayload?.invoke(bytes)?.let { Reading(signal.id, it, nowMs) }
            }
        }
        publishReadings(connection, decoded, nowMs)
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
     * answers, which costs a good part of every request. Some clones answer "?" or garbage to the
     * longer command, others hang until the timeout; any reply other than data or NO DATA on the first
     * try therefore drops the digit for the rest of this connection and repeats the request without
     * it. Keeping it after a timeout would make every request time out, force a reconnect and start
     * the same loop on the next connection. The digit is retried on the next connection, because a
     * reset adapter or a different one may accept it.
     */
    private suspend fun request(connection: Connection, command: String): ElmResult {
        if (connection.suffix == SuffixState.DISABLED || command.startsWith(AT_PREFIX, ignoreCase = true)) {
            return transmit(command)
        }
        val result = transmit(command + RESPONSE_COUNT_SUFFIX)
        if (connection.suffix == SuffixState.UNTESTED) {
            if (result !is ElmResult.Data && result !is ElmResult.NoData) {
                connection.suffix = SuffixState.DISABLED
                return transmit(command)
            }
            connection.suffix = SuffixState.CONFIRMED
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
        publishReadings(connection, decoded, nowMs)
    }

    private fun publishReadings(connection: Connection, decoded: List<Reading>, nowMs: Long) {
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

        /** SAE J1979: a Mode 01 request carries at most six PIDs, and only on CAN. */
        const val MAX_BATCH_PIDS = 6
        const val PROBE_BATCH_PIDS = 2
        const val BATCH_FAILURE_LIMIT = 2
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
