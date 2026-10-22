package nl.stijnvdpol.klinedash.core.obd

/**
 * Turns the hex lines of an [nl.stijnvdpol.klinedash.core.elm.ElmResult.Data] (headers off) into
 * whole OBD messages, whatever the bus:
 *  - K-line / J1850: every line is one message ("430171134900 00").
 *  - CAN (ISO 15765) multi-frame, as the ELM prints it: a three-digit length line ("00A") followed
 *    by numbered frame lines ("0:43040171...", "1:..."). The frames are joined and cut to the
 *    announced length (padding); a message with fewer bytes than announced is dropped. A numbered
 *    "0:" line without a length line starts a message too.
 *
 * Lines that are neither are skipped; the caller sees only complete, well-formed messages.
 */
internal object ObdMessages {

    private const val HEX_RADIX = 16

    private val HEX_BYTES = Regex("(?:[0-9A-F]{2})+")
    private val LENGTH_LINE = Regex("[0-9A-F]{3}")
    private val FRAME_LINE = Regex("([0-9A-F]):((?:[0-9A-F]{2})*)")

    fun fromLines(lines: List<String>): List<IntArray> {
        val messages = ArrayList<IntArray>()
        var announcedLength: Int? = null
        var pending: StringBuilder? = null

        fun finishPending() {
            val hex = pending?.toString().orEmpty()
            if (hex.isNotEmpty()) {
                val bytes = bytesOf(hex)
                val length = announcedLength
                // Fewer bytes than announced: frames went missing, so the message is not whole.
                if (length == null) messages += bytes else if (bytes.size >= length) messages += bytes.copyOf(length)
            }
            pending = null
            announcedLength = null
        }

        for (line in lines) {
            val frame = FRAME_LINE.matchEntire(line)
            when {
                LENGTH_LINE.matches(line) -> {
                    finishPending()
                    announcedLength = line.toInt(HEX_RADIX)
                    pending = StringBuilder()
                }
                frame != null -> {
                    val isFirstFrame = frame.groupValues[1] == "0"
                    if (isFirstFrame && pending?.isNotEmpty() == true) finishPending()
                    if (pending == null && isFirstFrame) pending = StringBuilder()
                    pending?.append(frame.groupValues[2])
                }
                HEX_BYTES.matches(line) -> {
                    finishPending()
                    messages += bytesOf(line)
                }
            }
        }
        finishPending()
        return messages
    }

    /** [hex] has been matched against whole hex bytes already, so it always converts. */
    private fun bytesOf(hex: String): IntArray = checkNotNull(ObdHex.toBytes(hex)) { "not hex bytes: $hex" }
}
