package nl.stijnvdpol.klinedash.core.exportformat

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import nl.stijnvdpol.klinedash.core.geo.KMH_PER_MPS
import nl.stijnvdpol.klinedash.core.trip.TripCsvFormat
import nl.stijnvdpol.klinedash.core.trip.TripColumn

/**
 * Racelogic VBO writer (opens in Circuit Tools, RaceChrono, RaceRender), per the Racelogic file
 * structure notes in docs/research/gps-laps-export.md section 3.2. Text with CR+LF line ends and
 * blank-line separated sections:
 *
 * ```
 * File created on 22/10/2026 at 14:03:07        (UTC)
 *
 * [header]            long channel names, one per line
 * [channel units]     units of the non-standard channels, same order (omitted without OBD channels)
 * [comments]
 * [column names]      short names, space separated
 * [data]              one row per distinct GPS fix, space separated
 * ```
 *
 * Standard columns: `sats time lat long velocity heading height`, then one column per OBD signal
 * (named by its id). Row format: `sats` 3 digits; `time` `hhmmss.sss` UTC time of day of the fix;
 * `lat` and `long` in MINUTES (degrees x 60) with 5 decimals, latitude positive north and
 * longitude positive WEST (reverse of the usual sign); `velocity` km/h; `heading` degrees;
 * `height` metres.
 *
 * Choices where the format cannot say "unknown": there is no altitude, so `height` is a constant
 * 0.00 (stated in the comments); a missing satellite count, speed or heading is 0; a missing OBD
 * value repeats the last known value (0 before the first). Samples without a GPS fix are skipped,
 * since a VBO row needs a position. The `[laptiming]` section is left out: its syntax is only partly
 * documented, and laps are recomputed on the PC from the position data.
 */
object VboWriter {
    private const val LINE_END = "\r\n"
    private const val MINUTES_PER_DEGREE = 60.0
    private const val NO_UNIT = "-"
    private const val COMMENT =
        "Times are UTC. Height is not recorded (always 0). A missing OBD value repeats the last known one."
    private val CREATED_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd/MM/yyyy 'at' HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HHmmss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val STANDARD_HEADER = listOf("satellites", "time", "latitude", "longitude", "velocity kmh", "heading", "height")
    private val STANDARD_COLUMNS = listOf("sats", "time", "lat", "long", "velocity", "heading", "height")

    fun write(trip: TripExportInput, out: Appendable) {
        val channels = trip.columns.map { it.id.toChannelName() }
        out.line("File created on ${CREATED_FORMAT.format(Instant.ofEpochMilli(trip.createdMs))}")
        out.section("[header]", STANDARD_HEADER + channels)
        if (trip.columns.isNotEmpty()) out.section("[channel units]", trip.columns.map { it.unit.toChannelUnit() })
        out.section("[comments]", listOf("K-Line Dash: ${trip.title.singleLine()}", COMMENT))
        out.section("[column names]", listOf((STANDARD_COLUMNS + channels).joinToString(" ")))
        out.section("[data]", emptyList())
        writeRows(trip, out)
    }

    private fun writeRows(trip: TripExportInput, out: Appendable) {
        val lastValues = HashMap<String, Double>()
        for (sample in trip.samples.withNewFix()) {
            val fix = sample.gps ?: continue
            lastValues.putAll(sample.values)
            val standard = listOf(
                String.format(Locale.US, "%03d", fix.satellites ?: 0),
                TIME_FORMAT.format(Instant.ofEpochMilli(fix.timeMs)),
                String.format(Locale.US, "%+012.5f", fix.lat * MINUTES_PER_DEGREE),
                String.format(Locale.US, "%+012.5f", -fix.lon * MINUTES_PER_DEGREE),
                String.format(Locale.US, "%07.3f", (fix.speedMps ?: 0.0) * KMH_PER_MPS),
                String.format(Locale.US, "%06.2f", fix.bearingDeg ?: 0.0),
                String.format(Locale.US, "%+09.2f", 0.0),
            )
            out.line((standard + trip.columns.map { channelValue(it, lastValues) }).joinToString(" "))
        }
    }

    private fun channelValue(column: TripColumn, lastValues: Map<String, Double>): String =
        TripCsvFormat.formatFixed(lastValues[column.id] ?: 0.0, column.decimals())

    private fun Appendable.line(text: String) {
        append(text).append(LINE_END)
    }

    private fun Appendable.section(name: String, lines: List<String>) {
        line("")
        line(name)
        lines.forEach { line(it) }
    }

    /** A channel name has no spaces: anything but letters, digits and underscore becomes an underscore. */
    private fun String.toChannelName(): String = replace(Regex("[^A-Za-z0-9_]"), "_")

    /** Units are plain ASCII words; an empty unit would make an empty line, which ends a section. */
    private fun String.toChannelUnit(): String =
        replace("°", "deg").replace(Regex("\\s+"), "_").ifEmpty { NO_UNIT }

    private fun String.singleLine(): String = replace(Regex("[\r\n]+"), " ")
}
