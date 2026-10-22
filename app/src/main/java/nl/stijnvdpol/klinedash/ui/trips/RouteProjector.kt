package nl.stijnvdpol.klinedash.ui.trips

import kotlin.math.floor
import nl.stijnvdpol.klinedash.core.geo.LatLon
import nl.stijnvdpol.klinedash.core.geo.LocalFrame

/** A point on the route field, in pixels from its top-left corner. */
internal data class ScreenPoint(val x: Float, val y: Float)

/** A route shorter than this in either direction is treated as this long, so a trip that stood still draws a dot, not NaN. */
private const val MIN_SPAN_M = 1.0

/**
 * Per axis, this share of the route positions at each end may lie outside the scale. The scale is
 * the one thing a single bad GPS fix that got past the plausibility check can ruin: with raw
 * minimum and maximum a fix 30 km away shrinks the real route to a dot. Trimming 0.5 % at each end
 * ignores up to that many stray fixes; the (decimated) route holds at most 2000 points, so it is up
 * to 9 per end, and fewer than 200 points are not trimmed at all. The cost is that the very tip of
 * a real route can poke into the margin of the field by a few metres, which the margin absorbs.
 */
private const val OUTLIER_FRACTION = 0.005

/**
 * Turns positions into pixels for a route field without a map: a local equirectangular projection
 * (metres east and north of the middle position, the same kind of frame the lap detector uses), scaled to fit
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
         * A projector that fits the [route] into a [widthPx] x [heightPx] field with [marginPx] on every
         * side, scaled by the central 99 % of its positions per axis (see [OUTLIER_FRACTION]); null when
         * there is nothing to draw or the field is not larger than its margins. [anchors] (the gate ends)
         * are always inside, so a gate never sticks out of the field.
         */
        fun fit(route: List<LatLon>, widthPx: Float, heightPx: Float, marginPx: Float, anchors: List<LatLon> = emptyList()): RouteProjector? {
            val availableWidth = widthPx - 2 * marginPx
            val availableHeight = heightPx - 2 * marginPx
            // The middle position, not the first: a stray first fix would make the flat frame inaccurate.
            val origin = route.getOrNull(route.size / 2) ?: anchors.firstOrNull()
            if (origin == null || availableWidth <= 0f || availableHeight <= 0f) return null
            val frame = LocalFrame(origin)
            val routePoints = route.map(frame::project)
            val anchorPoints = anchors.map(frame::project)
            val east = fitRange(routePoints.map { it.x }, anchorPoints.map { it.x })
            val north = fitRange(routePoints.map { it.y }, anchorPoints.map { it.y })
            val scale = minOf(
                availableWidth / maxOf(east.endInclusive - east.start, MIN_SPAN_M),
                availableHeight / maxOf(north.endInclusive - north.start, MIN_SPAN_M),
            )
            return RouteProjector(
                frame = frame,
                middleEastM = (east.start + east.endInclusive) / 2,
                middleNorthM = (north.start + north.endInclusive) / 2,
                pixelsPerMetre = scale,
                fieldCentre = ScreenPoint(widthPx / 2, heightPx / 2),
            )
        }

        /** The range of [route] values without [OUTLIER_FRACTION] at each end, widened to hold every one of [anchors]. */
        private fun fitRange(route: List<Double>, anchors: List<Double>): ClosedFloatingPointRange<Double> {
            val sorted = route.sorted()
            val trim = floor(OUTLIER_FRACTION * (sorted.size - 1)).toInt().coerceAtLeast(0)
            val bounds = anchors + listOfNotNull(sorted.getOrNull(trim), sorted.getOrNull(sorted.lastIndex - trim))
            return bounds.min()..bounds.max()
        }
    }
}
