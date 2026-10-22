package nl.stijnvdpol.klinedash.core.trip

import nl.stijnvdpol.klinedash.core.geo.GeoFix
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

    /** Appends one row for [nowMs] from [readings] and the latest GPS fix [gps], if the writer stores one. */
    fun writeSample(nowMs: Long, readings: Map<String, Reading>, gps: GeoFix? = null)
}

/**
 * Running totals of a trip, updated per sample; stored with the trip when it ends.
 * [distanceKm] comes from GPS where fixes exist and from integrated vehicle speed elsewhere.
 */
data class TripSummary(
    val startMs: Long,
    val endMs: Long,
    val sampleCount: Int,
    /** 0 when neither GPS nor vehicle speed was available. */
    val distanceKm: Double,
    val maxRpm: Double?,
    val maxSpeedKmh: Double?,
    val maxCoolantC: Double?,
)
