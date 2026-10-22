package nl.stijnvdpol.klinedash.ui.trips

import nl.stijnvdpol.klinedash.core.geo.LatLon
import nl.stijnvdpol.klinedash.core.geo.LocalFrame

/** A point on the route field, in pixels from its top-left corner. */
internal data class ScreenPoint(val x: Float, val y: Float)

/** A route shorter than this in either direction is treated as this long, so a trip that stood still draws a dot, not NaN. */
private const val MIN_SPAN_M = 1.0

/**
 * Turns positions into pixels for a route field without a map: a local equirectangular projection
 * (metres east and north of the first point, the same one the lap detector uses), scaled to fit
 * the field with a margin, north up, centred. One scale for both axes, so the shape is true.
 */
internal class RouteProjector private constructor(
    private val frame: LocalFrame,
    private val middleEastM: Double,
    private val middleNorthM: Double,
    private val pixelsPerMetre: Double,
    private val fieldCentre: ScreenPoint,
) {
    fun project(position: LatLon): ScreenPoint {
        val point = frame.project(position)
        return ScreenPoint(
            x = (fieldCentre.x + (point.x - middleEastM) * pixelsPerMetre).toFloat(),
            y = (fieldCentre.y - (point.y - middleNorthM) * pixelsPerMetre).toFloat(),
        )
    }

    companion object {
        /**
         * A projector that fits all [positions] into a [widthPx] x [heightPx] field with [marginPx] on
         * every side; null when there is nothing to draw or the field is not larger than its margins.
         * Pass the route and the gate ends together, so a gate never sticks out of the field.
         */
        fun fit(positions: List<LatLon>, widthPx: Float, heightPx: Float, marginPx: Float): RouteProjector? {
            val availableWidth = widthPx - 2 * marginPx
            val availableHeight = heightPx - 2 * marginPx
            if (positions.isEmpty() || availableWidth <= 0f || availableHeight <= 0f) return null
            val frame = LocalFrame(positions.first())
            val points = positions.map(frame::project)
            val minEast = points.minOf { it.x }
            val maxEast = points.maxOf { it.x }
            val minNorth = points.minOf { it.y }
            val maxNorth = points.maxOf { it.y }
            val scale = minOf(
                availableWidth / maxOf(maxEast - minEast, MIN_SPAN_M),
                availableHeight / maxOf(maxNorth - minNorth, MIN_SPAN_M),
            )
            return RouteProjector(
                frame = frame,
                middleEastM = (minEast + maxEast) / 2,
                middleNorthM = (minNorth + maxNorth) / 2,
                pixelsPerMetre = scale,
                fieldCentre = ScreenPoint(widthPx / 2, heightPx / 2),
            )
        }
    }
}
