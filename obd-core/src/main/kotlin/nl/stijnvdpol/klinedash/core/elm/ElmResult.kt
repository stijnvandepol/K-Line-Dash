package nl.stijnvdpol.klinedash.core.elm

/**
 * Classified outcome of one command sent to an ELM327. Produced by the parser from the raw
 * text before the '>' prompt. [raw] is always the untouched adapter text, for the debug log.
 */
sealed interface ElmResult {
    val raw: String

    /**
     * Usable payload. [lines] are the non-empty response lines with echo, "SEARCHING...",
     * "BUS INIT: ...OK" and similar status noise removed. For OBD responses each line is
     * normalised to upper-case hex with spaces removed (e.g. "410C1AF8"). CAN multi-frame replies
     * keep the ELM layout needed to reassemble them: a three-digit length line ("00A") and
     * numbered frames ("0:43040171..."); see diag.ObdMessages.
     * For AT commands lines are trimmed text (e.g. "ELM327 v1.5", "12.4V", "OK").
     */
    data class Data(val lines: List<String>, override val raw: String) : ElmResult

    /** "NO DATA": ECU did not answer (PID unsupported, ignition off, or bus asleep). */
    data class NoData(override val raw: String) : ElmResult

    /** "?": adapter did not understand the command (common for clones on ATLP, ATAT1, AT@1...). */
    data class Unknown(override val raw: String) : ElmResult

    /** "BUS INIT: ...ERROR" / "UNABLE TO CONNECT": K-line init failed (ignition off, wrong protocol). */
    data class InitError(override val raw: String) : ElmResult

    /** "BUS ERROR", "FB ERROR", "DATA ERROR", "<DATA ERROR", "RX ERROR", "BUS BUSY", "LV RESET", "ERR94"... */
    data class BusError(override val raw: String) : ElmResult

    /** "CAN ERROR": adapter tried a CAN protocol; on a K-line car usually means wrong protocol. */
    data class CanError(override val raw: String) : ElmResult

    /** "STOPPED": a new char arrived while the adapter was busy; the command was aborted. */
    data class Stopped(override val raw: String) : ElmResult

    /** "BUFFER FULL". */
    data class BufferFull(override val raw: String) : ElmResult

    /** No prompt within the timeout; [raw] holds whatever arrived. */
    data class Timeout(override val raw: String) : ElmResult

    /** Anything else we could not make sense of (garbage, half lines). */
    data class Garbage(override val raw: String) : ElmResult
}
