package nl.stijnvdpol.klinedash.core.diag

import nl.stijnvdpol.klinedash.core.elm.ElmResult

/**
 * Decodes service 09 replies (headers off) into text. Pure.
 *
 * K-line ECUs answer with one line per four data bytes, "49 PID COUNTER d d d d", counting up from
 * 01; the VIN starts behind three padding zeros. A CAN ECU sends one multi-frame message "49 PID 01
 * <data>". Both reduce to: join the data of the messages of the first ECU (the counter restarting
 * means a second ECU is answering), drop the zero padding.
 */
object VehicleInfoParser {

    private const val RESPONSE_VEHICLE_INFO = 0x49
    private const val HEADER_BYTES = 3 // response, PID, counter
    private const val MIN_PRINTABLE = 0x20
    private const val MAX_PRINTABLE = 0x7E
    private const val PID_VIN = 0x02
    private const val PID_ECU_NAME = 0x0A

    /** 17 characters, digits and capitals except I, O and Q (ISO 3779). */
    private val VIN_FORMAT = Regex("[A-HJ-NPR-Z0-9]{17}")

    /** The VIN in [data], or null when it is absent or not a valid VIN. */
    fun parseVin(data: ElmResult.Data): String? =
        text(data, PID_VIN).filter { it.code in MIN_PRINTABLE..MAX_PRINTABLE }.takeIf { VIN_FORMAT.matches(it) }

    /** The ECU name in [data], trimmed, or null when absent or empty. */
    fun parseEcuName(data: ElmResult.Data): String? =
        text(data, PID_ECU_NAME).filter { it.code in MIN_PRINTABLE..MAX_PRINTABLE }.trim().ifEmpty { null }

    /** All data bytes the first ECU sent for [pid] as characters, zeros removed. */
    private fun text(data: ElmResult.Data, pid: Int): String {
        val sb = StringBuilder()
        var lastCounter = 0
        for (message in ObdMessages.fromLines(data.lines)) {
            if (message.size < HEADER_BYTES || message[0] != RESPONSE_VEHICLE_INFO || message[1] != pid) continue
            if (message[2] <= lastCounter) break
            lastCounter = message[2]
            for (i in HEADER_BYTES until message.size) if (message[i] != 0) sb.append(message[i].toChar())
        }
        return sb.toString()
    }
}
