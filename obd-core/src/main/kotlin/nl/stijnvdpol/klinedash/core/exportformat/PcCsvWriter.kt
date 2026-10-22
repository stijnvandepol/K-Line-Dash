package nl.stijnvdpol.klinedash.core.exportformat

import nl.stijnvdpol.klinedash.core.geo.LapIndex
import nl.stijnvdpol.klinedash.core.trip.GpsColumns
import nl.stijnvdpol.klinedash.core.trip.LapStats
import nl.stijnvdpol.klinedash.core.trip.TripCsvFormat

/**
 * Tidy CSVs for Excel, LibreOffice and pandas. Same conventions as the trip file (UTC ISO time, '.'
 * decimals, `\n` line ends, header `<id> (<unit>)`), without a byte order mark: the caller may add
 * one when the target is Excel on Windows.
 *
 * - [writeTrip]: one row per sample, `time_iso,elapsed_s,lap,<signals>,<GPS columns>`; `lap` is the
 *   lap the sample falls in for the current gates and empty outside any lap.
 * - [writeLapSummary]: one row per lap.
 */
object PcCsvWriter {
    private const val LAP_COLUMN = "lap"
    private const val SECONDS_DECIMALS = 3
    private const val KM_DECIMALS = 3
    private const val SPEED_DECIMALS = 1
    private const val RPM_DECIMALS = 0
    private const val MS_PER_SECOND = 1000.0

    private val SUMMARY_HEADER = listOf(
        LAP_COLUMN, "valid", "start_iso", "lap_time (s)", "delta_best (s)", "distance (km)",
        "avg_speed (km/h)", "max_speed (km/h)", "min_speed (km/h)", "max_rpm (rpm)", "full_throttle (s)",
    )

    fun writeTrip(trip: TripExportInput, out: Appendable) {
        val signalNames = trip.columns.map { TripCsvFormat.columnName(it.id, it.unit) }
        val header = listOf(TripCsvFormat.TIME_COLUMN, TripCsvFormat.ELAPSED_COLUMN, LAP_COLUMN) + signalNames + GpsColumns.header
        out.row(header)
        val lapIndex = LapIndex(trip.laps)
        var originMs: Long? = null
        for (sample in trip.samples) {
            val origin = originMs ?: sample.timeMs.also { originMs = it }
            val signalCells = trip.columns.map { column ->
                sample.values[column.id]?.let { TripCsvFormat.formatFixed(it, column.decimals()) }.orEmpty()
            }
            val leading = listOf(
                TripCsvFormat.formatTime(sample.timeMs),
                TripCsvFormat.formatElapsedSeconds(sample.timeMs - origin),
                lapIndex.numberAt(sample.timeMs)?.toString().orEmpty(),
            )
            out.row(leading + signalCells + GpsColumns.cells(sample.gps, sample.timeMs))
        }
    }

    /**
     * Columns: `lap,valid,start_iso,lap_time (s),delta_best (s),distance (km),avg_speed (km/h),
     * max_speed (km/h),min_speed (km/h),max_rpm (rpm),full_throttle (s)` followed by `sector_N (s)`
     * for as many sectors as the laps have. `valid` is 1 or 0; `delta_best` is the lap time minus the
     * fastest valid lap and empty for invalid laps. Unknown values are empty cells.
     */
    fun writeLapSummary(laps: List<LapStats>, out: Appendable) {
        val sectorCount = laps.maxOfOrNull { it.lap.sectorTimesMs.size } ?: 0
        out.row(SUMMARY_HEADER + (1..sectorCount).map { "sector_$it (s)" })
        val bestMs = laps.filter { it.lap.isValid }.minOfOrNull { it.lap.lapTimeMs }
        for (stats in laps) {
            val lap = stats.lap
            val delta = if (lap.isValid && bestMs != null) seconds(lap.lapTimeMs - bestMs) else ""
            val cells = listOf(
                lap.number.toString(),
                if (lap.isValid) "1" else "0",
                TripCsvFormat.formatTime(lap.startMs),
                seconds(lap.lapTimeMs),
                delta,
                TripCsvFormat.formatFixed(stats.distanceKm, KM_DECIMALS),
                stats.averageSpeedKmh.fixed(SPEED_DECIMALS),
                stats.maxSpeedKmh.fixed(SPEED_DECIMALS),
                stats.minSpeedKmh.fixed(SPEED_DECIMALS),
                stats.maxRpm.fixed(RPM_DECIMALS),
                seconds(stats.fullThrottleTimeMs),
            )
            val sectors = (0 until sectorCount).map { lap.sectorTimesMs.getOrNull(it)?.let(::seconds).orEmpty() }
            out.row(cells + sectors)
        }
    }

    private fun seconds(ms: Long): String = TripCsvFormat.formatFixed(ms / MS_PER_SECOND, SECONDS_DECIMALS)

    private fun Double?.fixed(decimals: Int): String = this?.let { TripCsvFormat.formatFixed(it, decimals) }.orEmpty()

    private fun Appendable.row(fields: List<String>) {
        append(fields.joinToString(TripCsvFormat.SEPARATOR) { TripCsvFormat.escapeField(it) }).append(TripCsvFormat.LINE_END)
    }
}
