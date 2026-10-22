package nl.stijnvdpol.klinedash.core.obd

import nl.stijnvdpol.klinedash.core.elm.ElmResult

/**
 * Picks the data bytes of one OBD service/PID out of an [ElmResult.Data]. With headers off
 * (ATH0) a line is the response service (request mode + 0x40), the PID echo and then the data,
 * e.g. "410C1AF8" -> mode 1, PID 0x0C, data [0x1A, 0xF8].
 *
 * K-line adapters can deliver stale or foreign lines (another ECU, an earlier request), so lines
 * that do not start with the expected service and PID are ignored, as are lines that are not a
 * whole number of hex bytes.
 */
object ObdReply {

    private const val HEADER_BYTES = 2 // response service + PID echo

    /** Data bytes of the first line answering ([mode], [pid]); null if no line does. */
    fun payload(data: ElmResult.Data, mode: Int, pid: Int): IntArray? =
        data.lines.firstNotNullOfOrNull { dataBytes(it, mode, pid) }

    /**
     * Data bytes of every line answering ([mode], [pid]), in reply order. Several ECUs may answer
     * the same request (e.g. the supported-PID bitmaps), each on its own line.
     */
    fun payloads(data: ElmResult.Data, mode: Int, pid: Int): List<IntArray> =
        data.lines.mapNotNull { dataBytes(it, mode, pid) }

    private fun dataBytes(line: String, mode: Int, pid: Int): IntArray? {
        val bytes = ObdHex.toBytes(line) ?: return null
        if (bytes.size < HEADER_BYTES) return null
        if (bytes[0] != ObdHex.responseService(mode) || bytes[1] != pid) return null
        return bytes.copyOfRange(HEADER_BYTES, bytes.size)
    }
}
