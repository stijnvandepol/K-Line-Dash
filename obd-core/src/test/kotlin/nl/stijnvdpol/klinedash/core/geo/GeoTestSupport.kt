package nl.stijnvdpol.klinedash.core.geo

import kotlin.math.cos

/** A flat test world around 52.0 N, 5.0 E, addressed in metres east/north of that origin. */
object TestWorld {
    private const val ORIGIN_LAT = 52.0
    private const val ORIGIN_LON = 5.0
    private val metresPerDegree = Math.toRadians(1.0) * Geodesy.EARTH_RADIUS_M

    /** 2026-10-22T14:03:07.000Z */
    const val T0 = 1_792_677_787_000L

    fun position(eastM: Double, northM: Double): LatLon = LatLon(
        ORIGIN_LAT + northM / metresPerDegree,
        ORIGIN_LON + eastM / (metresPerDegree * cos(Math.toRadians(ORIGIN_LAT))),
    )

    fun fix(
        timeMs: Long,
        eastM: Double,
        northM: Double,
        speedMps: Double? = null,
        bearingDeg: Double? = null,
        accuracyM: Double? = null,
        satellites: Int? = null,
    ): GeoFix {
        val at = position(eastM, northM)
        return GeoFix(timeMs, at.lat, at.lon, speedMps, bearingDeg, accuracyM, satellites)
    }

    /** The start/finish gate used by the loop: 100 m along the south edge, crossed heading east. */
    val loopGate: Gate = Gate.fromPointAndHeading(position(100.0, 0.0), 90.0)

    /** A sector gate on the east edge, 100 m up, crossed heading north. */
    val loopSectorGate: Gate = Gate.fromPointAndHeading(position(300.0, 100.0), 0.0)
}

/** A 300 x 200 m rectangle driven counter-clockwise from its south-west corner: 1000 m per loop. */
object TestLoop {
    const val LENGTH_M = 1_000.0
    private const val WIDTH_M = 300.0
    private const val HEIGHT_M = 200.0

    /** Position (east, north) after [distanceM] along the loop, wrapping every [LENGTH_M]. */
    fun positionAt(distanceM: Double): Pair<Double, Double> {
        val d = distanceM % LENGTH_M
        return when {
            d < WIDTH_M -> d to 0.0
            d < WIDTH_M + HEIGHT_M -> WIDTH_M to d - WIDTH_M
            d < 2 * WIDTH_M + HEIGHT_M -> (WIDTH_M - (d - WIDTH_M - HEIGHT_M)) to HEIGHT_M
            else -> 0.0 to (HEIGHT_M - (d - 2 * WIDTH_M - HEIGHT_M))
        }
    }

    /**
     * Fixes every [stepMs] from [startMs] while the car drives [totalDistanceM] along the loop. The
     * speed of a step is [speedAt] of the distance where the step starts.
     */
    fun drive(
        totalDistanceM: Double,
        startMs: Long = TestWorld.T0,
        stepMs: Long = 1_000L,
        speedAt: (Double) -> Double,
    ): List<GeoFix> {
        val fixes = ArrayList<GeoFix>()
        var distance = 0.0
        var time = startMs
        while (distance <= totalDistanceM) {
            val (east, north) = positionAt(distance)
            fixes += TestWorld.fix(time, east, north)
            distance += speedAt(distance) * stepMs / 1000.0
            time += stepMs
        }
        return fixes
    }

    fun drive(totalDistanceM: Double, speedMps: Double): List<GeoFix> = drive(totalDistanceM) { speedMps }
}
