package nl.stijnvdpol.klinedash.core.transport

/**
 * Splits an incoming byte stream into ELM327 responses, one per '>' prompt.
 * Shared by every byte-oriented transport (BT Classic, BLE, Wi-Fi) so they frame identically.
 *
 * Cheap clones sometimes emit 0x00 or bytes with the high bit set between responses;
 * both are dropped here so the parser only ever sees printable ASCII plus CR/LF.
 *
 * The pending text is capped at [MAX_PENDING_CHARS]: no real response comes near it, so a clone that
 * streams without ever sending '>' (or a hostile device on an open adapter Wi-Fi) would otherwise
 * grow the heap until the app runs out of memory. Past the cap [feed] drops the text and throws
 * [PromptOverflowException]; the transport treats that as a lost link and the connection manager
 * reconnects.
 */
class PromptBuffer {
    private val current = StringBuilder()

    /**
     * Feeds [length] bytes from [bytes]; returns every response completed by them, in order.
     * Throws [PromptOverflowException] (after emptying the buffer) when more than
     * [MAX_PENDING_CHARS] arrive without a prompt; responses completed earlier in the same call are lost with it.
     */
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
                b == 0x0D || b == 0x0A || b in 0x20..0x7E -> append(b.toChar())
                // NUL, other control chars and non-ASCII garbage: ignore.
            }
        }
        return completed ?: emptyList()
    }

    /** Text received since the last prompt (useful for timeout diagnostics). */
    fun pending(): String = current.toString()

    fun clear() = current.setLength(0)

    private fun append(char: Char) {
        if (current.length >= MAX_PENDING_CHARS) {
            current.setLength(0)
            throw PromptOverflowException(MAX_PENDING_CHARS)
        }
        current.append(char)
    }

    companion object {
        /** Far above the longest real response (a multi-ECU VIN reply is a few hundred characters). */
        const val MAX_PENDING_CHARS = 4_096

        private const val PROMPT = 0x3E // '>'
    }
}

/** The adapter sent more than [limit] characters without a prompt; it is misbehaving or not an ELM327. */
class PromptOverflowException(limit: Int) :
    TransportException("The adapter sent more than $limit characters without finishing a response")
