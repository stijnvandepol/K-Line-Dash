package nl.stijnvdpol.klinedash.core.diag

import nl.stijnvdpol.klinedash.core.elm.ElmResult
import nl.stijnvdpol.klinedash.core.elm.isCanProtocol
import nl.stijnvdpol.klinedash.core.obd.ObdHex
import nl.stijnvdpol.klinedash.core.obd.ObdMessages

/** How an ECU lays out a code list reply; depends on the bus. */
enum class DtcFormat {
    /** K-line (ISO 9141-2, KWP) and J1850: each line is the response byte plus three 2-byte codes, padded with 0000. */
    KLINE,

    /** CAN (ISO 15765): response byte, a count byte, then 2 bytes per code, possibly over several frames. */
    CAN,
}

/**
 * Decodes the replies to service 03 (stored), 07 (pending) and 0A (permanent) with headers off.
 * Pure; the reply bytes are SAE J1979: the first two bits of the first code byte select P, C, B or
 * U, the next two the first digit (0-3), the remaining 12 bits are three hex digits.
 */
object DtcParser {

    private const val SERVICE_STORED = 0x03
    private const val SERVICE_PENDING = 0x07
    private const val SERVICE_PERMANENT = 0x0A
    private const val BYTES_PER_CODE = 2
    private const val LETTER_SHIFT = 6
    private const val LETTER_MASK = 0x03
    private const val DIGIT_SHIFT = 4
    private const val DIGIT_MASK = 0x03
    private const val NIBBLE_MASK = 0x0F
    private const val NIBBLE_BITS = 4
    private const val CODE_LETTERS = "PCBU"
    private const val HEX_DIGITS = "0123456789ABCDEF"

    /** The layout for an ELM protocol number: CAN for ISO 15765 (6-9), K-line/J1850 for everything else. */
    fun formatFor(protocolNumber: String): DtcFormat =
        if (isCanProtocol(protocolNumber)) DtcFormat.CAN else DtcFormat.KLINE

    /** The OBD service number requested for [kind]: 03, 07 or 0A. */
    fun serviceFor(kind: DtcKind): Int = when (kind) {
        DtcKind.STORED -> SERVICE_STORED
        DtcKind.PENDING -> SERVICE_PENDING
        DtcKind.PERMANENT -> SERVICE_PERMANENT
    }

    /**
     * The codes in [data] for the list [kind], unique and in reply order (several ECUs may report
     * the same code). Null when no line is a well-formed answer to that service (a negative
     * response, stale lines from another request), which is different from an empty list: the ECU
     * answered and has no codes.
     */
    fun parse(data: ElmResult.Data, kind: DtcKind, format: DtcFormat): List<Dtc>? {
        val responseByte = ObdHex.responseService(serviceFor(kind))
        val answers = ObdMessages.fromLines(data.lines)
            .filter { it.isNotEmpty() && it[0] == responseByte }
            .mapNotNull { codeBytes(it, format) }
        if (answers.isEmpty()) return null
        return answers.flatMap { bytes -> bytes.toList().chunked(BYTES_PER_CODE).mapNotNull { decode(it[0], it[1]) } }
            .distinct()
    }

    /** The code for bytes [high] and [low]; null for 0000, which only pads a reply. */
    fun decode(high: Int, low: Int): Dtc? {
        if (high == 0 && low == 0) return null
        val letter = CODE_LETTERS[(high shr LETTER_SHIFT) and LETTER_MASK]
        val firstDigit = (high shr DIGIT_SHIFT) and DIGIT_MASK
        val hexDigits = listOf(high and NIBBLE_MASK, (low shr NIBBLE_BITS) and NIBBLE_MASK, low and NIBBLE_MASK)
        return Dtc("$letter$firstDigit" + hexDigits.joinToString("") { HEX_DIGITS[it].toString() })
    }

    /** The code bytes of one message (response byte first), or null when it is malformed for [format]. */
    private fun codeBytes(message: IntArray, format: DtcFormat): IntArray? = when (format) {
        DtcFormat.KLINE -> message.copyOfRange(1, message.size).takeIf { it.size % BYTES_PER_CODE == 0 }
        DtcFormat.CAN -> canCodeBytes(message)
    }

    /** CAN: byte 1 counts the codes; frame padding behind them is ignored, a short message yields what it has. */
    private fun canCodeBytes(message: IntArray): IntArray? {
        if (message.size < 2) return null
        val available = (message.size - 2) / BYTES_PER_CODE
        val count = minOf(message[1], available)
        return message.copyOfRange(2, 2 + count * BYTES_PER_CODE)
    }
}
