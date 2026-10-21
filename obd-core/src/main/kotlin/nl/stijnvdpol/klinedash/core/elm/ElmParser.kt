package nl.stijnvdpol.klinedash.core.elm

/**
 * Turns the raw text an ELM327 (or clone) sent before its '>' prompt into an [ElmResult].
 * Pure and stateless; every regex is compiled once.
 *
 * Lessons from cheap clones that shaped this:
 *  - echo is on until ATE0 has been accepted, and some clones ignore ATE0 entirely, so the echo
 *    of the command (spaces/case may differ) is always stripped when it is the first line;
 *  - "SEARCHING..." and "BUS INIT: ...OK" are status noise that may precede data, and some
 *    clones glue the data onto the "BUS INIT" line;
 *  - the "<" in "<DATA ERROR" is appended to a data line, so such a line is an error, not data;
 *  - after a brown-out a clone may answer an unrelated command with its "ELM327 v1.5" banner.
 *
 * Empty replies (nothing but CR/LF/spaces, or nothing left after removing the echo) are
 * [ElmResult.Garbage] for both OBD and AT commands: every command we send is answered with at
 * least "OK", data or an error text, so silence means a lost or mangled reply.
 */
object ElmParser {

    private val VOLTAGE = Regex("""^(\d{1,2})(?:[.,](\d{1,2}))?\s*[Vv]?$""")
    private val ERROR_CODE = Regex("""^ERR\d+$""")
    private val CAN_FRAME_LINE = Regex("""^\s*([0-9A-Fa-f])\s*:(.*)$""")
    private const val CAN_LENGTH_LINE_DIGITS = 3
    private val PROTOCOL_NUMBER = Regex("""^[0-9A-C]$""")

    private val BUS_ERROR_TEXTS = setOf(
        "BUS ERROR", "FB ERROR", "DATA ERROR", "RX ERROR", "BUS BUSY", "LV RESET",
        // Low-power activity monitor fired: command was not executed.
        "ACT ALERT",
    )

    private enum class Status { UNKNOWN, NO_DATA, INIT_ERROR, CAN_ERROR, STOPPED, BUFFER_FULL, BUS_ERROR }

    /**
     * Parses the reply to [command] (as sent, without CR) from [raw].
     * OBD commands are hex strings such as "010C"; anything starting with "AT" is an AT command.
     */
    fun parse(command: String, raw: String): ElmResult {
        val lines = splitLines(raw)
        if (lines.isEmpty()) return ElmResult.Garbage(raw)

        val cmd = compact(command)
        val isAt = cmd.startsWith("AT")
        // A banner is the legitimate answer to ATZ/ATWS/ATI, but never to an OBD request.
        val hasBanner = looksLikeReset(raw)
        if (!isAt && hasBanner) return ElmResult.Garbage(raw)
        val isReset = cmd == "ATZ" || cmd == "ATWS"

        var status: Status? = null
        val payload = ArrayList<String>(lines.size)

        for (i in lines.indices) {
            val line = lines[i]
            if (i == 0 && cmd.isNotEmpty() && compact(line) == cmd) continue // echo

            val upper = line.uppercase()
            if (upper.startsWith("SEARCHING")) continue

            if (upper.startsWith("BUS INIT")) {
                val rest = line.substring(BUS_INIT_PREFIX_LENGTH)
                    .trimStart { it == ':' || it == '.' || it <= ' ' }
                val restUpper = rest.uppercase()
                if (restUpper.startsWith("ERROR")) {
                    if (status == null) status = Status.INIT_ERROR
                } else if (restUpper.startsWith(BUS_INIT_OK)) {
                    val tail = rest.substring(BUS_INIT_OK.length).trim { it <= ' ' }
                    if (tail.isNotEmpty()) addPayload(payload, tail, isAt)
                }
                // "BUS INIT: ..." with neither OK nor ERROR is a truncated progress line: ignore.
                continue
            }

            val lt = line.indexOf('<')
            val lineStatus = if (lt >= 0) {
                classify(line.substring(lt + 1).trim { it <= ' ' }.uppercase()) ?: Status.BUS_ERROR
            } else {
                classify(upper)
            }
            if (lineStatus != null) {
                // Clones prefix a stray "?" to the ATZ banner; the banner is what counts there.
                if (lineStatus == Status.UNKNOWN && isReset && hasBanner) continue
                if (status == null) status = lineStatus
                continue
            }

            addPayload(payload, line, isAt)
        }

        status?.let { return toResult(it, raw) }

        if (isAt) {
            return if (payload.isEmpty()) ElmResult.Garbage(raw) else ElmResult.Data(payload, raw)
        }

        val hex = ArrayList<String>(payload.size)
        for (line in payload) {
            val normalised = normaliseHex(line)
            if (normalised != null) hex.add(normalised)
        }
        return if (hex.isEmpty()) ElmResult.Garbage(raw) else ElmResult.Data(hex, raw)
    }

    /**
     * True if [raw] contains the adapter's "ELM327" power-up banner. Seen as the reply to
     * something else, it means the adapter reset itself (low voltage, loose contact) and lost
     * its AT configuration, so the session must re-run init. The legitimate replies to
     * ATZ/ATWS/ATI also contain it; callers must account for that.
     */
    fun looksLikeReset(raw: String): Boolean = raw.contains("ELM327", ignoreCase = true)

    /** Parses one ATRV line such as "12.4V", "12.4 V", "12,4V" or "13.9"; null for "?" or garbage. */
    fun parseVoltage(text: String): Double? {
        val m = VOLTAGE.matchEntire(text.trim { it <= ' ' }) ?: return null
        val fraction = m.groupValues[2].ifEmpty { "0" }
        return "${m.groupValues[1]}.$fraction".toDoubleOrNull()
    }

    /**
     * Extracts the protocol number from one ATDPN line: "A3" (auto-detected) and "3" (fixed)
     * both give "3". Returns null if the text is not a protocol character (0-9, A-C).
     */
    fun parseProtocolNumber(text: String): String? {
        val t = text.filter { it > ' ' }.uppercase()
        val body = if (t.length == 2 && t[0] == 'A') t.substring(1) else t
        return if (PROTOCOL_NUMBER.matches(body)) body else null
    }

    /** Strips the "AUTO, " prefix the adapter adds to ATDP when the protocol was auto-detected. */
    fun parseProtocolName(text: String): String {
        val t = text.trim { it <= ' ' }
        return if (t.startsWith(AUTO_PREFIX, ignoreCase = true)) t.substring(AUTO_PREFIX.length).trim() else t
    }

    private const val BUS_INIT_PREFIX_LENGTH = 8 // "BUS INIT"
    private const val BUS_INIT_OK = "OK"
    private const val AUTO_PREFIX = "AUTO,"

    private fun toResult(status: Status, raw: String): ElmResult = when (status) {
        Status.UNKNOWN -> ElmResult.Unknown(raw)
        Status.NO_DATA -> ElmResult.NoData(raw)
        Status.INIT_ERROR -> ElmResult.InitError(raw)
        Status.CAN_ERROR -> ElmResult.CanError(raw)
        Status.STOPPED -> ElmResult.Stopped(raw)
        Status.BUFFER_FULL -> ElmResult.BufferFull(raw)
        Status.BUS_ERROR -> ElmResult.BusError(raw)
    }

    private fun classify(upper: String): Status? = when {
        upper.isNotEmpty() && upper.all { it == '?' } -> Status.UNKNOWN
        upper == "NO DATA" -> Status.NO_DATA
        // Some clones print "NOT CONNECTED" where a genuine ELM says "UNABLE TO CONNECT".
        upper == "UNABLE TO CONNECT" || upper == "NOT CONNECTED" -> Status.INIT_ERROR
        upper == "CAN ERROR" -> Status.CAN_ERROR
        upper == "STOPPED" -> Status.STOPPED
        upper == "BUFFER FULL" -> Status.BUFFER_FULL
        upper in BUS_ERROR_TEXTS || ERROR_CODE.matches(upper) -> Status.BUS_ERROR
        else -> null
    }

    /** AT text keeps only printable ASCII (clone garbage dropped); OBD lines are validated later. */
    private fun addPayload(payload: MutableList<String>, line: String, isAt: Boolean) {
        if (isAt) {
            val printable = line.filter { it in ' '..'~' }.trim()
            if (printable.isNotEmpty()) payload.add(printable)
        } else {
            payload.add(line)
        }
    }

    /**
     * Upper-case hex without spaces, or null if the line is not a whole number of hex bytes.
     * Two CAN multi-frame forms are kept as the ELM prints them, because the message they belong
     * to can only be reassembled with them: the length line ("00A", three hex digits) and numbered
     * frames ("0:43040171..."), which keep their "N:" prefix.
     */
    private fun normaliseHex(line: String): String? {
        val frame = CAN_FRAME_LINE.matchEntire(line)
        if (frame != null) {
            val bytes = hexDigits(frame.groupValues[2]) ?: return null
            return if (bytes.length % 2 != 0) null else "${frame.groupValues[1].uppercase()}:$bytes"
        }
        val digits = hexDigits(line) ?: return null
        return when {
            digits.length == CAN_LENGTH_LINE_DIGITS -> digits
            digits.isEmpty() || digits.length % 2 != 0 -> null
            else -> digits
        }
    }

    /** [text] as upper-case hex digits without spaces, or null if it contains anything else. */
    private fun hexDigits(text: String): String? {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                in '0'..'9', in 'A'..'F' -> sb.append(c)
                in 'a'..'f' -> sb.append(c.uppercaseChar())
                ' ', '\t' -> Unit
                else -> return null
            }
        }
        return sb.toString()
    }

    private fun compact(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) if (c > ' ') sb.append(c.uppercaseChar())
        return sb.toString()
    }

    private fun splitLines(raw: String): List<String> {
        val out = ArrayList<String>(4)
        var start = 0
        for (i in 0..raw.length) {
            if (i == raw.length || raw[i] == '\r' || raw[i] == '\n') {
                if (i > start) {
                    val t = raw.substring(start, i).trim { it <= ' ' }
                    if (t.isNotEmpty()) out.add(t)
                }
                start = i + 1
            }
        }
        return out
    }
}
