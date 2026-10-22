package nl.stijnvdpol.klinedash.core.trip

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.obd.Signal

/** Default age after which a reading no longer counts: slow signals refresh about every 15 s. */
const val DEFAULT_TRIP_FRESHNESS_MS = 30_000L

/** The reading's value when it exists and is no older than [freshnessMs]; null otherwise. */
internal fun Map<String, Reading>.freshValue(signalId: String, nowMs: Long, freshnessMs: Long): Double? {
    val reading = this[signalId] ?: return null
    return reading.value.takeIf { nowMs - reading.timestampMs <= freshnessMs }
}

/**
 * CSV layout (UTF-8 text is the caller's job, lines end with `\n`):
 *
 * - Header: `time_iso,elapsed_s,` then one column per signal named `<id> (<unit>)`, e.g.
 *   `coolant (°C)`, `rpm (rpm)`; a signal without a unit is just `<id>`, e.g. `mil_on`. The id is
 *   the stable machine-readable part, the unit is for humans. Fields containing a comma, quote or
 *   line break are quoted.
 * - `time_iso` is ISO-8601 in UTC with milliseconds (`2026-10-22T14:03:07.250Z`); `elapsed_s` is the
 *   time since the first sample with one decimal.
 * - Values use the signal's own decimals with a '.' separator whatever the device locale is.
 *   A value that is missing or older than [freshnessMs] is an empty cell.
 */
class CsvTripWriter(
    private val out: Appendable,
    private val freshnessMs: Long = DEFAULT_TRIP_FRESHNESS_MS,
) : TripWriter {

    private var columns: List<Signal>? = null
    private var firstSampleMs: Long? = null

    override fun writeHeader(columns: List<Signal>) {
        check(this.columns == null) { "The CSV header was already written" }
        this.columns = columns
        val names = listOf(TIME_COLUMN, ELAPSED_COLUMN) + columns.map(::columnName)
        writeLine(names.map(::escapeField))
    }

    override fun writeSample(nowMs: Long, readings: Map<String, Reading>) {
        val columns = checkNotNull(columns) { "writeHeader must be called before writeSample" }
        val origin = firstSampleMs ?: nowMs.also { firstSampleMs = it }
        val cells = columns.map { column ->
            readings.freshValue(column.id, nowMs, freshnessMs)
                ?.takeIf { it.isFinite() }
                ?.let { formatValue(it, column.decimals) }
                .orEmpty()
        }
        writeLine(listOf(formatTime(nowMs), formatElapsedSeconds(nowMs - origin)) + cells)
    }

    private fun writeLine(fields: List<String>) {
        out.append(fields.joinToString(SEPARATOR)).append(LINE_END)
    }

    private fun columnName(signal: Signal): String =
        if (signal.unit.isEmpty()) signal.id else "${signal.id} (${signal.unit})"

    private fun formatTime(epochMs: Long): String = TIME_FORMAT.format(Instant.ofEpochMilli(epochMs))

    private fun formatElapsedSeconds(elapsedMs: Long): String =
        String.format(Locale.US, "%.1f", elapsedMs / MS_PER_SECOND)

    private fun formatValue(value: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)

    private fun escapeField(field: String): String {
        val needsQuotes = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + field.replace("\"", "\"\"") + "\"" else field
    }

    private companion object {
        const val TIME_COLUMN = "time_iso"
        const val ELAPSED_COLUMN = "elapsed_s"
        const val SEPARATOR = ","
        const val LINE_END = "\n"
        const val MS_PER_SECOND = 1000.0
        val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
    }
}
