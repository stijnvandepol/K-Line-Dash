package nl.stijnvdpol.klinedash.core.trip

import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.obd.Reading

/**
 * One row of a trip: what was known at [timeMs], whether it comes live from the poller or from a
 * parsed CSV. [values] holds the fresh OBD values by signal id (missing = unknown), [gps] the latest
 * fix if there was one. The same fix can appear in several consecutive samples; consumers compare
 * `gps.timeMs` to tell a new fix from a repeated one.
 */
data class TripSample(
    val timeMs: Long,
    val values: Map<String, Double>,
    val gps: GeoFix? = null,
)

/** Builds a sample from live [readings], keeping only those no older than [freshnessMs]. */
fun tripSampleOf(
    nowMs: Long,
    readings: Map<String, Reading>,
    gps: GeoFix? = null,
    freshnessMs: Long = DEFAULT_TRIP_FRESHNESS_MS,
): TripSample {
    val fresh = HashMap<String, Double>()
    for (signalId in readings.keys) {
        readings.freshValue(signalId, nowMs, freshnessMs)?.takeIf { it.isFinite() }?.let { fresh[signalId] = it }
    }
    return TripSample(nowMs, fresh, gps)
}
