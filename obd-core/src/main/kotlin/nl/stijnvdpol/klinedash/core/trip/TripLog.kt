package nl.stijnvdpol.klinedash.core.trip

import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.obd.Signal

/**
 * Writes one trip as CSV: a header row, then one row per sample with the time and every column's
 * latest fresh value (empty when the value is missing or stale). Implemented by [CsvTripWriter].
 *
 * The caller owns the underlying stream and closes it; the writer only appends text.
 */
interface TripWriter {
    /** Writes the header for [columns]; must be called once before [writeSample]. */
    fun writeHeader(columns: List<Signal>)

    /** Appends one row for [nowMs] from [readings]. */
    fun writeSample(nowMs: Long, readings: Map<String, Reading>)
}

/** Running totals of a trip, updated per sample; stored with the trip when it ends. */
data class TripSummary(
    val startMs: Long,
    val endMs: Long,
    val sampleCount: Int,
    /** Integrated from vehicle speed; 0 when speed was not polled. */
    val distanceKm: Double,
    val maxRpm: Double?,
    val maxSpeedKmh: Double?,
    val maxCoolantC: Double?,
)
