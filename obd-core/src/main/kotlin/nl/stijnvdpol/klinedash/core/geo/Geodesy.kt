package nl.stijnvdpol.klinedash.core.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle helpers on a spherical Earth (error below 0.5 % against WGS84, far below GPS noise). */
object Geodesy {
    const val EARTH_RADIUS_M = 6_371_008.8

    private const val FULL_CIRCLE_DEG = 360.0

    /** Haversine distance between [a] and [b] in metres. */
    fun distanceM(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    /** Initial bearing from [from] to [to] in degrees clockwise from north, in [0, 360). */
    fun bearingDeg(from: LatLon, to: LatLon): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return normalizeDegrees(Math.toDegrees(atan2(y, x)))
    }

    /** The point reached from [from] after [distanceM] metres along [bearingDeg]. */
    fun destination(from: LatLon, bearingDeg: Double, distanceM: Double): LatLon {
        val angular = distanceM / EARTH_RADIUS_M
        val bearing = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(from.lat)
        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
        val lon2 = Math.toRadians(from.lon) +
            atan2(sin(bearing) * sin(angular) * cos(lat1), cos(angular) - sin(lat1) * sin(lat2))
        return LatLon(Math.toDegrees(lat2), normalizeLongitude(Math.toDegrees(lon2)))
    }

    /** [degrees] wrapped into [0, 360). */
    fun normalizeDegrees(degrees: Double): Double {
        val wrapped = degrees % FULL_CIRCLE_DEG
        return if (wrapped < 0) wrapped + FULL_CIRCLE_DEG else wrapped
    }

    private fun normalizeLongitude(degrees: Double): Double {
        val half = FULL_CIRCLE_DEG / 2
        return normalizeDegrees(degrees + half) - half
    }
}
