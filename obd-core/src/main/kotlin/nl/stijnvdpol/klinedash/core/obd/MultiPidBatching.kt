package nl.stijnvdpol.klinedash.core.obd

import nl.stijnvdpol.klinedash.core.elm.ElmResult

private const val MODE_CURRENT_DATA = 0x01

/** A single CAN frame holds 7 data bytes; the response service byte "41" takes one. */
private const val SINGLE_FRAME_BLOCK_BYTES = 6

/** SAE J1979: a Mode 01 request carries at most six PIDs, and only on CAN. */
private const val MAX_BATCH_PIDS = 6
private const val PROBE_BATCH_PIDS = 2
private const val BATCH_FAILURE_LIMIT = 2

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
    command = ObdHex.request(MODE_CURRENT_DATA, *members.mapNotNull { it.pid }.toIntArray()),
    intervalMs = PollCommand.EVERY_ROUND,
    signals = members.flatMap { it.signals },
    members = members,
)

/**
 * How multi-PID requests have fared on one connection; they are never tried off CAN. The first
 * batch of a connection doubles as the probe and is limited to [PROBE_BATCH_PIDS] PIDs.
 */
internal class BatchNegotiation(isCan: Boolean) {
    private enum class State { UNTESTED, ON, OFF }

    private var state = if (isCan) State.UNTESTED else State.OFF

    /** Batch requests that failed in a row while single requests still worked. */
    private var failuresInARow = 0

    val isOff: Boolean get() = state == State.OFF

    /** The most PIDs the next batch may carry. */
    val maxPids: Int get() = if (state == State.UNTESTED) PROBE_BATCH_PIDS else MAX_BATCH_PIDS

    /** A batch was answered in full. Returns true when it was the probe, the first one of the connection. */
    fun recordAnswered(): Boolean {
        val wasProbe = state == State.UNTESTED
        state = State.ON
        failuresInARow = 0
        return wasProbe
    }

    /**
     * A batch fell short ([failure]) and its members were then sent one by one. That only counts
     * against batching when the single requests were answered ([singlesAnswered]; otherwise the ECU
     * is just silent, e.g. ignition off), or when the adapter said "?". The probe and a "?" turn
     * batching off at once; later, [BATCH_FAILURE_LIMIT] failed rounds in a row do. Returns true
     * when this failure switched batching off.
     */
    fun recordFailure(failure: ElmResult, singlesAnswered: Boolean): Boolean {
        if (!singlesAnswered && failure !is ElmResult.Unknown) return false
        failuresInARow++
        val isGivingUp = state == State.UNTESTED ||
            failure is ElmResult.Unknown ||
            failuresInARow >= BATCH_FAILURE_LIMIT
        if (isGivingUp) state = State.OFF
        return isGivingUp
    }
}
