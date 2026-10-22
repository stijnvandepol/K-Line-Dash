package nl.stijnvdpol.klinedash.core.geo

import kotlin.math.roundToLong

/** Where a segment between two fixes crossed a gate. [fraction] is 0..1 along the segment. */
data class GateCrossing(val timeMs: Long, val fraction: Double)

/**
 * A timing line between [a] and [b], used for start/finish and sector lines.
 *
 * It only counts when crossed in the direction the gate was set with: from the right of A to B to
 * the left of A to B (walking from A towards B, you cross to your left). [fromPointAndHeading]
 * builds a gate that way, so the driving direction at set-time is the valid direction.
 */
data class Gate(val a: LatLon, val b: LatLon) {

    private val frame = LocalFrame(midpoint(a, b))
    private val pointA = frame.project(a)
    private val line = frame.project(b) - pointA

    val midpoint: LatLon get() = midpoint(a, b)

    /**
     * The crossing of the straight segment [from] to [to] with this gate, or null when it does not
     * cross, crosses against the valid direction, or the fixes are not in time order. The crossing
     * time is interpolated linearly between the two fixes' times. A fix exactly on the gate belongs
     * to the segment that starts there, so it is never counted twice.
     */
    fun crossing(from: GeoFix, to: GeoFix): GateCrossing? {
        val start = frame.project(from.position)
        val step = frame.project(to.position) - start
        val denominator = step.cross(line)
        if (denominator >= 0.0 || to.timeMs <= from.timeMs) return null
        val toGate = pointA - start
        val alongStep = toGate.cross(line) / denominator
        val alongGate = toGate.cross(step) / denominator
        if (alongStep < 0.0 || alongStep >= 1.0 || alongGate < 0.0 || alongGate > 1.0) return null
        val timeMs = from.timeMs + (alongStep * (to.timeMs - from.timeMs)).roundToLong()
        return GateCrossing(timeMs, alongStep)
    }

    companion object {
        const val DEFAULT_WIDTH_M = 30.0

        /** A gate through [point], perpendicular to [headingDeg] and [widthM] wide. */
        fun fromPointAndHeading(point: LatLon, headingDeg: Double, widthM: Double = DEFAULT_WIDTH_M): Gate {
            require(widthM > 0.0) { "Gate width must be positive: $widthM" }
            val half = widthM / 2
            val left = Geodesy.destination(point, headingDeg - RIGHT_ANGLE_DEG, half)
            val right = Geodesy.destination(point, headingDeg + RIGHT_ANGLE_DEG, half)
            return Gate(left, right)
        }

        private const val RIGHT_ANGLE_DEG = 90.0

        private fun midpoint(a: LatLon, b: LatLon) = LatLon((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
    }
}
