package nl.stijnvdpol.klinedash.core.obd

import nl.stijnvdpol.klinedash.core.diag.ObdMessages
import nl.stijnvdpol.klinedash.core.elm.ElmResult

/**
 * Splits the reply to a multi-PID Mode 01 request ("01 0C 0D" on CAN, SAE J1979) into the data
 * bytes of each PID. The ECU sends one message "41 0C d d 0D d": response service once, then for
 * every PID its number followed by its data, with nothing in between. The only way to find where a
 * block ends is the PID's known data length, so a wrong length would shift every block after it and
 * show wrong values without any error. A message is therefore only used when it splits exactly:
 * every block a requested PID, no PID twice, no byte left over. Anything else (an ECU that sends
 * another length than J1979 says, a reply cut short, two interleaved multi-frame answers that the
 * ELM prints without headers) drops the whole message, and the poller falls back to single requests.
 *
 * The message may come as a single line or, once it exceeds one CAN frame, as the ELM's numbered
 * multi-frame lines; [ObdMessages] reassembles both.
 */
object MultiPidResponse {

    private const val RESPONSE_CURRENT_DATA = 0x41

    /**
     * PID to data bytes for every PID of the messages in [data] that split exactly. When several
     * ECUs answer, the first block per PID wins; an ECU may answer only some of the PIDs.
     *
     * @param requestedLengths the PIDs of the request, each with its exact number of data bytes
     *   (see [Signal.pidDataLength])
     */
    fun split(data: ElmResult.Data, requestedLengths: Map<Int, Int>): Map<Int, IntArray> {
        val blocks = LinkedHashMap<Int, IntArray>()
        for (message in ObdMessages.fromLines(data.lines)) {
            if (message.isEmpty() || message[0] != RESPONSE_CURRENT_DATA) continue
            splitMessage(message, requestedLengths)?.forEach { (pid, bytes) -> blocks.putIfAbsent(pid, bytes) }
        }
        return blocks
    }

    /** The blocks of one message, or null when it does not split exactly into requested PIDs. */
    private fun splitMessage(message: IntArray, requestedLengths: Map<Int, Int>): Map<Int, IntArray>? {
        val blocks = LinkedHashMap<Int, IntArray>()
        var index = 1
        while (index < message.size) {
            val pid = message[index]
            val length = requestedLengths[pid] ?: return null
            val end = index + 1 + length
            if (end > message.size || pid in blocks) return null
            blocks[pid] = message.copyOfRange(index + 1, end)
            index = end
        }
        return blocks
    }
}
