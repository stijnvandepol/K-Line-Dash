package nl.stijnvdpol.klinedash.core.geo

/** Kilometres per hour in one metre per second. */
internal const val KMH_PER_MPS = 3.6

private const val MAX_LATITUDE = 90.0
private const val MAX_LONGITUDE = 180.0

/** True when [lat] and [lon] are finite and inside the WGS84 range. */
fun isValidPosition(lat: Double, lon: Double): Boolean =
    lat.isFinite() && lon.isFinite() && lat in -MAX_LATITUDE..MAX_LATITUDE && lon in -MAX_LONGITUDE..MAX_LONGITUDE

/** A WGS84 position in decimal degrees. */
data class LatLon(val lat: Double, val lon: Double) {
    init {
        require(isValidPosition(lat, lon)) { "Not a valid position: $lat, $lon" }
    }
}

/**
 * One GPS fix. Only [timeMs] and the position are always known.
 *
 * @property timeMs GPS time of the fix in UTC epoch milliseconds (not the head unit's wall clock,
 *   which is often wrong when offline).
 * @property speedMps Doppler ground speed in metres per second.
 * @property bearingDeg direction of travel, degrees clockwise from true north.
 * @property accuracyM horizontal accuracy radius (68 %) in metres.
 */
data class GeoFix(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
    val accuracyM: Double? = null,
    val satellites: Int? = null,
) {
    init {
        require(isValidPosition(lat, lon)) { "Not a valid position: $lat, $lon" }
    }

    val position: LatLon get() = LatLon(lat, lon)
}
