package nl.stijnvdpol.klinedash.core.geo

import kotlin.math.cos

/** A point in a [LocalFrame]: metres east ([x]) and north ([y]) of the frame's origin. */
data class PointM(val x: Double, val y: Double) {
    operator fun minus(other: PointM): PointM = PointM(x - other.x, y - other.y)

    /** z component of the 2D cross product; positive when [other] points counter-clockwise of this. */
    fun cross(other: PointM): Double = x * other.y - y * other.x
}

/**
 * Equirectangular projection around [origin]: positions become metres east/north of it. The error
 * stays below 0.1 % within a few kilometres, which is all a gate and two consecutive fixes need.
 */
class LocalFrame(private val origin: LatLon) {
    private val metresPerDegree = Math.toRadians(1.0) * Geodesy.EARTH_RADIUS_M
    private val eastScale = metresPerDegree * cos(Math.toRadians(origin.lat))

    fun project(position: LatLon): PointM =
        PointM((position.lon - origin.lon) * eastScale, (position.lat - origin.lat) * metresPerDegree)
}
