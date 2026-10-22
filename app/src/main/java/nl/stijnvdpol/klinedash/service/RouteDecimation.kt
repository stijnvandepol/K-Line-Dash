package nl.stijnvdpol.klinedash.service

import java.util.PriorityQueue
import kotlin.math.hypot
import nl.stijnvdpol.klinedash.core.geo.LatLon
import nl.stijnvdpol.klinedash.core.geo.LocalFrame
import nl.stijnvdpol.klinedash.core.geo.PointM

/** The most points the route of a trip is reduced to for drawing. */
const val MAX_ROUTE_POINTS = 2_000

/** Points that deviate less than this from the simplified line are not worth a point on a screen. */
private const val MIN_DEVIATION_M = 0.5

/**
 * Reduces [points] (a route in driving order) to at most [maxPoints] for drawing, keeping the shape:
 * Douglas-Peucker simplification that always adds the point furthest from the current line next, so
 * it stops exactly at the budget instead of needing a tolerance guess. The first and last point are
 * always kept, the order is kept, and a route that is already short enough comes back unchanged.
 * It also stops early when the remaining points lie within [MIN_DEVIATION_M] of the line.
 *
 * Distances are measured on a flat frame around the first point, which is accurate enough for
 * drawing a route of any length a car drives. Typical cost is O(n log n).
 */
fun decimateRoute(points: List<LatLon>, maxPoints: Int = MAX_ROUTE_POINTS): List<LatLon> {
    require(maxPoints >= 2) { "A route needs at least its two end points: $maxPoints" }
    if (points.size <= maxPoints) return points
    val frame = LocalFrame(points.first())
    val projected = points.map(frame::project)
    val kept = BooleanArray(points.size)
    kept[0] = true
    kept[points.lastIndex] = true
    var keptCount = 2
    val queue = PriorityQueue<Split>(compareByDescending { it.deviationM })
    furthestPoint(projected, 0, points.lastIndex)?.let(queue::add)
    while (keptCount < maxPoints) {
        val split = queue.poll() ?: break
        if (split.deviationM < MIN_DEVIATION_M) break
        kept[split.index] = true
        keptCount++
        furthestPoint(projected, split.from, split.index)?.let(queue::add)
        furthestPoint(projected, split.index, split.to)?.let(queue::add)
    }
    return points.filterIndexed { index, _ -> kept[index] }
}

/** The point between [from] and [to] that is furthest from the line from -> to, at [deviationM]. */
private class Split(val from: Int, val to: Int, val index: Int, val deviationM: Double)

private fun furthestPoint(points: List<PointM>, from: Int, to: Int): Split? {
    if (to - from < 2) return null
    var bestIndex = from + 1
    var bestM = -1.0
    for (index in from + 1 until to) {
        val distanceM = distanceToSegmentM(points[index], points[from], points[to])
        if (distanceM > bestM) {
            bestM = distanceM
            bestIndex = index
        }
    }
    return Split(from, to, bestIndex, bestM)
}

private fun distanceToSegmentM(point: PointM, start: PointM, end: PointM): Double {
    val line = end - start
    val offset = point - start
    val lengthSquared = line.x * line.x + line.y * line.y
    val along = if (lengthSquared == 0.0) 0.0 else ((offset.x * line.x + offset.y * line.y) / lengthSquared).coerceIn(0.0, 1.0)
    return hypot(offset.x - along * line.x, offset.y - along * line.y)
}
