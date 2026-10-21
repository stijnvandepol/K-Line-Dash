package nl.stijnvdpol.klinedash.core.transport

/**
 * Splits an incoming byte stream into ELM327 responses, one per '>' prompt.
 * Shared by every byte-oriented transport (BT Classic, BLE, Wi-Fi) so they frame identically.
 *
 * Cheap clones sometimes emit 0x00 or bytes with the high bit set between responses;
 * both are dropped here so the parser only ever sees printable ASCII plus CR/LF.
 */
class PromptBuffer {
    private val current = StringBuilder()

    /** Feeds [length] bytes from [bytes]; returns every response completed by them, in order. */
    fun feed(bytes: ByteArray, length: Int = bytes.size): List<String> {
        var completed: MutableList<String>? = null
        for (i in 0 until length) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b == PROMPT -> {
                    if (completed == null) completed = ArrayList(1)
                    completed.add(current.toString())
                    current.setLength(0)
                }
                b == 0x0D || b == 0x0A -> current.append(b.toChar())
                b in 0x20..0x7E -> current.append(b.toChar())
                // NUL, other control chars and non-ASCII garbage: ignore.
            }
        }
        return completed ?: emptyList()
    }

    /** Text received since the last prompt (useful for timeout diagnostics). */
    fun pending(): String = current.toString()

    fun clear() = current.setLength(0)

    private companion object {
        const val PROMPT = 0x3E // '>'
    }
}
