package nl.stijnvdpol.klinedash.core.trip

import java.io.BufferedReader
import java.io.Reader

/** One OBD signal column of a trip CSV: [id] is the stable key, [unit] is empty when there is none. */
data class TripColumn(val id: String, val unit: String)

/**
 * A parsed trip CSV. [columns] are the OBD signal columns (time, elapsed and GPS columns excluded).
 * [samples] is read lazily from the reader the CSV was opened with, so it can be walked once and
 * the reader has to stay open until then.
 */
class TripCsv(val columns: List<TripColumn>, val samples: Sequence<TripSample>)

private const val BYTE_ORDER_MARK = '﻿'
private val GPS_COLUMN_IDS = setOf(
    GpsColumns.LAT, GpsColumns.LON, GpsColumns.SPEED, GpsColumns.HEADING, GpsColumns.ACCURACY,
    GpsColumns.SATELLITES, GpsColumns.AGE,
)

/**
 * Reads a trip CSV in the format of [CsvTripWriter], with or without GPS columns.
 *
 * Tolerant by design, because trip files are cut off by power loss and edited by hand:
 * - a leading UTF-8 byte order mark is ignored;
 * - only lines that end with a line feed count: a last line without one was cut off while it was
 *   written, its final number may be truncated, so it is dropped (as the trip recovery does);
 * - columns may be missing (no GPS columns, no elapsed column) and cells may be empty: an empty or
 *   non-numeric cell means "unknown" and is left out of [TripSample.values];
 * - a row with an unreadable time is skipped; a row shorter than the header counts as having empty
 *   cells for the missing ones;
 * - without a `time_iso` column no row can be placed in time, so no samples are returned.
 */
fun readTripCsv(reader: Reader): TripCsv {
    val lines = terminatedLines(reader)
    val header = if (lines.hasNext()) TripCsvFormat.splitLine(lines.next().removePrefix(BYTE_ORDER_MARK.toString())) else emptyList()
    val layout = CsvLayout(header)
    val samples = lines.asSequence().mapNotNull { layout.parseRow(TripCsvFormat.splitLine(it)) }
    return TripCsv(layout.signals, samples)
}

/** Which header column holds what. */
private class CsvLayout(header: List<String>) {
    private val names = header.map { TripCsvFormat.parseColumnName(it) }
    private val indexById: Map<String, Int> = names.withIndex().associate { (index, name) -> name.first to index }
    private val timeIndex = indexById[TripCsvFormat.TIME_COLUMN]

    val signals: List<TripColumn> = names
        .filter { (id, _) -> id != TripCsvFormat.TIME_COLUMN && id != TripCsvFormat.ELAPSED_COLUMN && id !in GPS_COLUMN_IDS }
        .map { (id, unit) -> TripColumn(id, unit) }

    fun parseRow(cells: List<String>): TripSample? {
        val timeMs = timeIndex?.let { cells.getOrNull(it) }?.let(TripCsvFormat::parseTime) ?: return null
        val values = HashMap<String, Double>()
        for (signal in signals) {
            cell(cells, signal.id)?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { values[signal.id] = it }
        }
        return TripSample(timeMs, values, GpsColumns.parse(timeMs) { cell(cells, it) })
    }

    private fun cell(cells: List<String>, id: String): String? =
        indexById[id]?.let { cells.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
}

/** The lines of [reader] that end with `\n` (a `\r` before it is dropped); an unterminated rest is not returned. */
private fun terminatedLines(reader: Reader): Iterator<String> = iterator {
    val buffered = reader as? BufferedReader ?: BufferedReader(reader)
    val line = StringBuilder()
    while (true) {
        val code = buffered.read()
        if (code < 0) return@iterator
        if (code == '\n'.code) {
            yield(line.toString().removeSuffix("\r"))
            line.setLength(0)
        } else {
            line.append(code.toChar())
        }
    }
}
