package nl.stijnvdpol.klinedash.core.trip

import nl.stijnvdpol.klinedash.core.geo.GeoFix
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
 * - With [includeGps] the header ends with seven GPS columns:
 *   `lat (deg),lon (deg),gps_speed (km/h),heading (deg),gps_acc (m),sats,gps_age (ms)`. `lat`/`lon`
 *   have 7 decimals, `gps_age` is the row time minus the fix time. A row without a fix, or a fix
 *   without speed, heading, accuracy or satellites, has empty cells. Files written without
 *   [includeGps] (older versions) simply lack these columns; [readTripCsv] reads both.
 * - There is no `lap` column: laps depend on the start/finish gate, which the user may change
 *   after the drive, so they are derived from the fixes later and added by the exporters.
 * - `time_iso` is ISO-8601 in UTC with milliseconds (`2026-10-22T14:03:07.250Z`); `elapsed_s` is the
 *   time since the first sample with one decimal.
 * - Values use the signal's own decimals with a '.' separator whatever the device locale is.
 *   A value that is missing or older than [freshnessMs] is an empty cell.
 */
class CsvTripWriter(
    private val out: Appendable,
    private val freshnessMs: Long = DEFAULT_TRIP_FRESHNESS_MS,
    private val includeGps: Boolean = false,
) : TripWriter {

    private var columns: List<Signal>? = null
    private var firstSampleMs: Long? = null

    override fun writeHeader(columns: List<Signal>) {
        check(this.columns == null) { "The CSV header was already written" }
        this.columns = columns
        val signalNames = columns.map { TripCsvFormat.columnName(it.id, it.unit) }
        val names = listOf(TripCsvFormat.TIME_COLUMN, TripCsvFormat.ELAPSED_COLUMN) + signalNames + gpsHeader()
        writeLine(names.map(TripCsvFormat::escapeField))
    }

    override fun writeSample(nowMs: Long, readings: Map<String, Reading>, gps: GeoFix?) {
        val columns = checkNotNull(columns) { "writeHeader must be called before writeSample" }
        val origin = firstSampleMs ?: nowMs.also { firstSampleMs = it }
        val cells = columns.map { column ->
            readings.freshValue(column.id, nowMs, freshnessMs)
                ?.takeIf { it.isFinite() }
                ?.let { TripCsvFormat.formatFixed(it, column.decimals) }
                .orEmpty()
        }
        val gpsCells = if (includeGps) GpsColumns.cells(gps, nowMs) else emptyList()
        writeLine(listOf(TripCsvFormat.formatTime(nowMs), formatElapsedSeconds(nowMs - origin)) + cells + gpsCells)
    }

    private fun gpsHeader(): List<String> = if (includeGps) GpsColumns.header else emptyList()

    private fun writeLine(fields: List<String>) {
        out.append(fields.joinToString(TripCsvFormat.SEPARATOR)).append(TripCsvFormat.LINE_END)
    }

    private fun formatElapsedSeconds(elapsedMs: Long): String =
        TripCsvFormat.formatFixed(elapsedMs / MS_PER_SECOND, 1)

    private companion object {
        const val MS_PER_SECOND = 1000.0
    }
}
