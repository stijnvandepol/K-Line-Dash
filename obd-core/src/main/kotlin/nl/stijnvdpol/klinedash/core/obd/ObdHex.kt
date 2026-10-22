package nl.stijnvdpol.klinedash.core.obd

/**
 * The hex text of OBD requests and replies (SAE J1979, headers off), in one place for every
 * request builder and reply decoder of this module.
 */
internal object ObdHex {

    private const val HEX_RADIX = 16
    private const val CHARS_PER_BYTE = 2
    private const val RESPONSE_SERVICE_OFFSET = 0x40

    /** The bytes of [hex] ("410C1AF8"); null when it has an odd length or a character that is not 0-9, A-F or a-f. */
    fun toBytes(hex: String): IntArray? {
        if (hex.length % CHARS_PER_BYTE != 0) return null
        val bytes = IntArray(hex.length / CHARS_PER_BYTE)
        for (i in bytes.indices) {
            val high = hexDigit(hex[i * CHARS_PER_BYTE])
            val low = hexDigit(hex[i * CHARS_PER_BYTE + 1])
            if (high < 0 || low < 0) return null
            bytes[i] = high * HEX_RADIX + low
        }
        return bytes
    }

    /** A request as the ELM327 takes it, every byte as two upper-case hex digits: (0x01, 0x0C) gives "010C". */
    fun request(vararg bytes: Int): String = bytes.joinToString("") { "%02X".format(it) }

    /** The service byte of a positive response to [service]: the request service plus 0x40 ("01" is answered by "41"). */
    fun responseService(service: Int): Int = service + RESPONSE_SERVICE_OFFSET

    private fun hexDigit(char: Char): Int = when (char) {
        in '0'..'9', in 'A'..'F', in 'a'..'f' -> char.digitToInt(HEX_RADIX)
        else -> -1
    }
}
