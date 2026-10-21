package nl.stijnvdpol.klinedash.core.log

import kotlinx.coroutines.flow.SharedFlow

/** Direction/kind of a raw communication log line. */
enum class LogKind { TX, RX, INFO, ERROR }

/** One line in the raw ELM communication log shown on the debug screen. */
data class LogEntry(
    /** System.currentTimeMillis() when recorded. */
    val timestampMs: Long,
    val kind: LogKind,
    /** TX: the command without CR. RX: the raw adapter text before the prompt, untouched. */
    val text: String,
)

/**
 * Bounded in-memory log of everything sent to and received from the adapter.
 * Implemented in obd-core (ring buffer); the debug screen observes [entries] and can export [snapshot].
 */
interface CommLog {
    /** Emits every new entry as it is recorded (replay = 0; use [snapshot] for history). */
    val entries: SharedFlow<LogEntry>

    fun record(kind: LogKind, text: String)

    /** Oldest-first copy of the retained entries. */
    fun snapshot(): List<LogEntry>

    fun clear()

    /** Plain-text export: one line per entry, "HH:mm:ss.SSS TX ATZ"; CR/LF inside RX shown as \r \n. */
    fun exportText(): String
}
