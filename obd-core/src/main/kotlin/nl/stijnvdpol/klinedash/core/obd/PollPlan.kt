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

/**
 * How long [nowMs] is past the due time of this command. Both times are on the poller's monotonic
 * clock: on a stepping wall clock a backward step would make this negative for hours.
 */
private fun PollCommand.overdueMs(lastAttemptMs: Map<String, Long>, nowMs: Long): Long {
    val last = lastAttemptMs[command] ?: return Long.MAX_VALUE
    return nowMs - last - intervalMs
}

/** The rotating slot: the most overdue non-FAST command, or null when none is due yet. */
internal fun pickRotating(commands: List<PollCommand>, lastAttemptMs: Map<String, Long>, nowMs: Long): PollCommand? =
    commands.filter { !it.isEveryRound && it.overdueMs(lastAttemptMs, nowMs) >= 0 }
        .maxByOrNull { it.overdueMs(lastAttemptMs, nowMs) }

/**
 * The commands of one round. Normally every FAST command once, then the rotating slot if something
 * is due.
 *
 * With a [repeatedCommand] (the RPM command, on a connection that cannot batch: K-line, J1850, a
 * CAN adapter that refused multi-PID) and at least one other FAST command, RPM goes out twice per
 * round, evenly spread: [rpm, first other, rpm, slot]. The tachometer and the shift light are what
 * the driver watches, and at 0.5 s per request a plain round of three FAST commands would update
 * them only every 2 s. The FAST commands after the first other one give up their place in the round
 * and compete for the slot instead, ahead of the MEDIUM and SLOW commands: when both want it they
 * share it, so with one slot per round a demoted command (throttle) is polled every third round
 * and the others still get theirs. Without a [repeatedCommand], or when it is not a FAST command
 * (nobody demanded RPM), no command is ever sent twice in a round.
 */
internal fun planRound(
    commands: List<PollCommand>,
    lastAttemptMs: Map<String, Long>,
    nowMs: Long,
    repeatedCommand: String? = null,
): List<PollCommand> {
    val fast = commands.filter { it.isEveryRound }
    val repeated = fast.firstOrNull { it.command == repeatedCommand }
    if (repeated == null || fast.size < 2) {
        val slot = pickRotating(commands, lastAttemptMs, nowMs) ?: return fast
        return fast + slot
    }
    val others = fast.filter { it !== repeated }
    val slot = pickSlotWithDemoted(commands, others.drop(1), lastAttemptMs, nowMs)
    return listOfNotNull(repeated, others.first(), repeated, slot)
}

/**
 * The slot of a round in which FAST commands were [demoted] into it: the most overdue of them (the
 * one polled longest ago) once [ROTATING_SLOTS_PER_DEMOTED] rotating commands had a slot since a
 * demoted one did, the rotating command otherwise. Coolant is a main field and drives the overheat
 * warning, so it must not wait twice as long because throttle took every other slot: at 0.5 s per
 * request this keeps coolant at about 15 s and throttle at about 6 s. With a single rotating command
 * the two simply take turns (its own earlier slots leave no separate trace in [lastAttemptMs]).
 */
private fun pickSlotWithDemoted(
    commands: List<PollCommand>,
    demoted: List<PollCommand>,
    lastAttemptMs: Map<String, Long>,
    nowMs: Long,
): PollCommand? {
    val rotating = pickRotating(commands, lastAttemptMs, nowMs)
    val oldestDemoted = demoted.minByOrNull { lastAttemptMs[it.command] ?: Long.MIN_VALUE } ?: return rotating
    if (rotating == null) return oldestDemoted
    val demotedLastMs = demoted.maxOf { lastAttemptMs[it.command] ?: Long.MIN_VALUE }
    val rotatingLastMs = commands.filter { !it.isEveryRound }.map { lastAttemptMs[it.command] ?: Long.MIN_VALUE }
    val slotsNeeded = if (rotatingLastMs.size >= ROTATING_SLOTS_PER_DEMOTED) ROTATING_SLOTS_PER_DEMOTED else 1
    // At or after, so that when neither was ever sent the demoted FAST command goes first.
    val rotatingSlotsSince = rotatingLastMs.count { it >= demotedLastMs }
    return if (rotatingSlotsSince < slotsNeeded) rotating else oldestDemoted
}

/** How many rotating (MEDIUM/SLOW) slots go between two slots of a demoted FAST command. */
private const val ROTATING_SLOTS_PER_DEMOTED = 2

/** Time until the first non-FAST command is due; only meaningful when such a command exists. */
internal fun nextDueInMs(commands: List<PollCommand>, lastAttemptMs: Map<String, Long>, nowMs: Long): Long =
    commands.filter { !it.isEveryRound }.minOf { -it.overdueMs(lastAttemptMs, nowMs) }.coerceAtLeast(1)
