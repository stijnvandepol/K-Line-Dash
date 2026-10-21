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

    private const val RESPONSE_SERVICE_OFFSET = 0x40
    private const val HEADER_BYTES = 2 // response service + PID echo
    private const val HEX_RADIX = 16
    private const val CHARS_PER_BYTE = 2

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
        val bytes = toBytes(line) ?: return null
        if (bytes.size < HEADER_BYTES) return null
        if (bytes[0] != mode + RESPONSE_SERVICE_OFFSET || bytes[1] != pid) return null
        return bytes.copyOfRange(HEADER_BYTES, bytes.size)
    }

    /** Null when [line] has an odd length or a non-hex character. */
    private fun toBytes(line: String): IntArray? {
        if (line.length % CHARS_PER_BYTE != 0) return null
        val bytes = IntArray(line.length / CHARS_PER_BYTE)
        for (i in bytes.indices) {
            val high = Character.digit(line[i * CHARS_PER_BYTE], HEX_RADIX)
            val low = Character.digit(line[i * CHARS_PER_BYTE + 1], HEX_RADIX)
            if (high < 0 || low < 0) return null
            bytes[i] = high * HEX_RADIX + low
        }
        return bytes
    }
}
