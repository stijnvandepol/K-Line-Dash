package nl.stijnvdpol.klinedash.core.trip

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.geo.isValidPosition

/** Text conventions shared by every CSV this module writes or reads: UTC, '.' decimals, `\n` ends. */
internal object TripCsvFormat {
    const val TIME_COLUMN = "time_iso"
    const val ELAPSED_COLUMN = "elapsed_s"
    const val SEPARATOR = ","
    const val LINE_END = "\n"

    private const val SEPARATOR_CHAR = ','
    private const val QUOTE = '"'
    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val COLUMN_NAME = Regex("^(.*?) \\((.*)\\)$")

    /** ISO-8601 in UTC with milliseconds, e.g. `2026-10-22T14:03:07.250Z`. */
    fun formatTime(epochMs: Long): String = TIME_FORMAT.format(Instant.ofEpochMilli(epochMs))

    /** The epoch milliseconds of a [formatTime] text, or null when it is not a valid ISO instant. */
    fun parseTime(text: String): Long? = try {
        Instant.parse(text).toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }

    fun formatFixed(value: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", value)

    /** `<id> (<unit>)`, or just the id for a signal without a unit. */
    fun columnName(id: String, unit: String): String = if (unit.isEmpty()) id else "$id ($unit)"

    /** Splits a [columnName] back into id and unit; a name without parentheses has an empty unit. */
    fun parseColumnName(name: String): Pair<String, String> {
        val match = COLUMN_NAME.matchEntire(name) ?: return name to ""
        return match.groupValues[1] to match.groupValues[2]
    }

    fun escapeField(field: String): String {
        val needsQuotes = field.any { it == SEPARATOR_CHAR || it == QUOTE || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + field.replace("\"", "\"\"") + "\"" else field
    }

    /** Splits one CSV line into fields, honouring double quotes (a quote inside is written twice). */
    fun splitLine(line: String): List<String> {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var isQuoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                isQuoted && char == QUOTE && line.getOrNull(index + 1) == QUOTE -> field.append(QUOTE).also { index++ }
                char == QUOTE -> isQuoted = !isQuoted
                char == SEPARATOR_CHAR && !isQuoted -> fields.add(field.toString()).also { field.setLength(0) }
                else -> field.append(char)
            }
            index++
        }
        fields.add(field.toString())
        return fields
    }
}

/**
 * The GPS columns that follow the signal columns of a trip CSV, in this order:
 * `lat (deg),lon (deg),gps_speed (km/h),heading (deg),gps_acc (m),sats,gps_age (ms)`.
 * `gps_age` is the row time minus the fix time, so the fix's own time can be recovered exactly.
 * A row without a fix has empty cells; so do the optional parts a fix lacks.
 */
internal object GpsColumns {
    const val LAT = "lat"
    const val LON = "lon"
    const val SPEED = "gps_speed"
    const val HEADING = "heading"
    const val ACCURACY = "gps_acc"
    const val SATELLITES = "sats"
    const val AGE = "gps_age"

    val header: List<String> = listOf(
        TripCsvFormat.columnName(LAT, "deg"),
        TripCsvFormat.columnName(LON, "deg"),
        TripCsvFormat.columnName(SPEED, "km/h"),
        TripCsvFormat.columnName(HEADING, "deg"),
        TripCsvFormat.columnName(ACCURACY, "m"),
        SATELLITES,
        TripCsvFormat.columnName(AGE, "ms"),
    )

    private const val POSITION_DECIMALS = 7
    private const val METRIC_DECIMALS = 1
    private const val KMH_PER_MPS = 3.6

    fun cells(gps: GeoFix?, rowTimeMs: Long): List<String> {
        if (gps == null) return List(header.size) { "" }
        return listOf(
            TripCsvFormat.formatFixed(gps.lat, POSITION_DECIMALS),
            TripCsvFormat.formatFixed(gps.lon, POSITION_DECIMALS),
            gps.speedMps.cell(KMH_PER_MPS),
            gps.bearingDeg.cell(1.0),
            gps.accuracyM.cell(1.0),
            gps.satellites?.toString().orEmpty(),
            (rowTimeMs - gps.timeMs).coerceAtLeast(0L).toString(),
        )
    }

    /** The fix in one row, or null when its position cells are empty or not a valid position. */
    fun parse(rowTimeMs: Long, cell: (String) -> String?): GeoFix? {
        val lat = cell(LAT)?.toDoubleOrNull() ?: return null
        val lon = cell(LON)?.toDoubleOrNull() ?: return null
        if (!isValidPosition(lat, lon)) return null
        val ageMs = cell(AGE)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        return GeoFix(
            timeMs = rowTimeMs - ageMs,
            lat = lat,
            lon = lon,
            speedMps = cell(SPEED)?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { it / KMH_PER_MPS },
            bearingDeg = cell(HEADING)?.toDoubleOrNull()?.takeIf { it.isFinite() },
            accuracyM = cell(ACCURACY)?.toDoubleOrNull()?.takeIf { it.isFinite() },
            satellites = cell(SATELLITES)?.toIntOrNull(),
        )
    }

    private fun Double?.cell(factor: Double): String =
        this?.takeIf { it.isFinite() }?.let { TripCsvFormat.formatFixed(it * factor, METRIC_DECIMALS) }.orEmpty()
}
