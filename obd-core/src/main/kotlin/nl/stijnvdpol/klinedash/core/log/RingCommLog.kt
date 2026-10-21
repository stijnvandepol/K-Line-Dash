package nl.stijnvdpol.klinedash.core.log

import java.util.TimeZone
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Thread-safe [CommLog] backed by a bounded ring: once [capacity] entries are held the oldest
 * one is dropped. The live [entries] flow never blocks the recorder; a slow collector simply
 * loses the oldest undelivered entries (history stays available through [snapshot]).
 *
 * @param clock timestamp source, injectable so tests get deterministic exports.
 * @param timeZone zone used by [exportText] for the HH:mm:ss.SSS prefix.
 */
class RingCommLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val timeZone: TimeZone = TimeZone.getDefault(),
) : CommLog {

    init {
        require(capacity > 0) { "capacity must be > 0" }
    }

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>()
    private val flow = MutableSharedFlow<LogEntry>(
        replay = 0,
        extraBufferCapacity = LIVE_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val entries: SharedFlow<LogEntry> = flow.asSharedFlow()

    override fun record(kind: LogKind, text: String) {
        val entry = LogEntry(clock(), kind, text)
        // tryEmit inside the lock so the flow order matches the ring order across threads.
        synchronized(lock) {
            if (buffer.size >= capacity) buffer.removeFirst()
            buffer.addLast(entry)
            flow.tryEmit(entry)
        }
    }

    override fun snapshot(): List<LogEntry> = synchronized(lock) { buffer.toList() }

    override fun clear() {
        synchronized(lock) { buffer.clear() }
    }

    override fun exportText(): String {
        val out = StringBuilder()
        for (e in snapshot()) {
            appendTime(out, e.timestampMs)
            out.append(' ').append(e.kind.name).append(' ')
            for (c in e.text) {
                when (c) {
                    '\r' -> out.append("\\r")
                    '\n' -> out.append("\\n")
                    else -> out.append(c)
                }
            }
            out.append('\n')
        }
        return out.toString()
    }

    private fun appendTime(out: StringBuilder, timestampMs: Long) {
        val local = timestampMs + timeZone.getOffset(timestampMs)
        val msOfDay = Math.floorMod(local, DAY_MS).toInt()
        val h = msOfDay / HOUR_MS
        val m = msOfDay / MINUTE_MS % 60
        val s = msOfDay / SECOND_MS % 60
        val ms = msOfDay % SECOND_MS
        out.append(pad(h, 2)).append(':').append(pad(m, 2)).append(':')
            .append(pad(s, 2)).append('.').append(pad(ms, 3))
    }

    private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')

    companion object {
        const val DEFAULT_CAPACITY = 5000
        private const val DAY_MS = 86_400_000L
        private const val HOUR_MS = 3_600_000
        private const val MINUTE_MS = 60_000
        private const val SECOND_MS = 1_000

        /** Live entries a slow collector may lag behind before the oldest are dropped. */
        private const val LIVE_BUFFER_CAPACITY = 256
    }
}
