package nl.stijnvdpol.klinedash.core.obd

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

internal fun Signal.isSupportedBy(supportedPids: Set<Int>): Boolean = pid == null || pid in supportedPids

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
