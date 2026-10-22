package nl.stijnvdpol.klinedash.core.obd

import nl.stijnvdpol.klinedash.core.diag.ObdMessages
import nl.stijnvdpol.klinedash.core.elm.ElmResult

/**
 * Splits the reply to a multi-PID Mode 01 request ("01 0C 0D" on CAN, SAE J1979) into the data
 * bytes of each PID. The ECU sends one message "41 0C d d 0D d": response service once, then for
 * every PID its number followed by its data, with nothing in between. The only way to find where a
 * block ends is the PID's known data length, so a PID that is not in the length table stops the
 * split (everything behind it is unreadable).
 *
 * The message may come as a single line or, once it exceeds one CAN frame, as the ELM's numbered
 * multi-frame lines; [ObdMessages] reassembles both.
 */
object MultiPidResponse {

    private const val RESPONSE_CURRENT_DATA = 0x41

    /**
     * PID to data bytes for every complete block in [data]. When several ECUs answer, the first
     * block per PID wins. A truncated last block (reply cut short) is dropped, so a PID present in
     * the result always has all its bytes.
     *
     * @param dataLengths PID to its exact number of data bytes, see [Signal.pidDataLength]
     */
    fun split(data: ElmResult.Data, dataLengths: Map<Int, Int>): Map<Int, IntArray> {
        val blocks = LinkedHashMap<Int, IntArray>()
        for (message in ObdMessages.fromLines(data.lines)) {
            if (message.isEmpty() || message[0] != RESPONSE_CURRENT_DATA) continue
            splitMessage(message, dataLengths, blocks)
        }
        return blocks
    }

    private fun splitMessage(message: IntArray, dataLengths: Map<Int, Int>, into: MutableMap<Int, IntArray>) {
        var index = 1
        while (index < message.size) {
            val pid = message[index]
            val length = dataLengths[pid] ?: return
            val end = index + 1 + length
            if (end > message.size) return
            into.putIfAbsent(pid, message.copyOfRange(index + 1, end))
            index = end
        }
    }
}
