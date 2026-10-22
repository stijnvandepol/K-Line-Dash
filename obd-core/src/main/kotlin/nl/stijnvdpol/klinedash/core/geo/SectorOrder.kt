package nl.stijnvdpol.klinedash.core.geo

/**
 * Where sector gates lie along a lap. Lap detection only counts sector gates in the order they are
 * listed, so a list that is not in driving order silently loses every sector time. The order is
 * therefore taken from a driven lap: the position of a gate is the distance from the start of the
 * lap trace to where the trace crosses it.
 *
 * A lap trace is a list of fixes in time order that starts at (or just before) the start/finish
 * crossing; see [LapTimingState.referenceLap] and [LapDetector.currentLapFixes].
 */
object SectorOrder {
    /** The driven distance along [lap]; pieces that are not plausible driving ([GpsSegment]) add nothing. */
    fun lapLengthM(lap: List<GeoFix>): Double {
        var total = 0.0
        for ((from, to) in lap.zipWithNext()) {
            if (GpsSegment.isPlausible(from, to)) total += Geodesy.distanceM(from.position, to.position)
        }
        return total
    }

    /**
     * The distance along [lap] at which it first crosses [gate] in the gate's valid direction, or null
     * when it never does (the lap was driven elsewhere, or has not reached the gate yet).
     */
    fun positionAlongLapM(gate: Gate, lap: List<GeoFix>): Double? {
        var travelledM = 0.0
        for ((from, to) in lap.zipWithNext()) {
            if (!GpsSegment.isPlausible(from, to)) continue
            val segmentM = Geodesy.distanceM(from.position, to.position)
            val crossing = gate.crossing(from, to)
            if (crossing != null) return travelledM + crossing.fraction * segmentM
            travelledM += segmentM
        }
        return null
    }

    /**
     * [items] sorted by [positionOf] (distance along a lap), nearest to the start first. Items
     * without a position (null) keep their relative order and go last, which is where a gate lies
     * that a lap still in progress has not reached. Equal positions keep their order too.
     */
    fun <T> sortedByPosition(items: List<T>, positionOf: (T) -> Double?): List<T> {
        // Measured once per item: positionOf walks the whole lap trace.
        val positions = items.map { positionOf(it) ?: Double.POSITIVE_INFINITY }
        return items.indices.sortedWith(compareBy<Int> { positions[it] }.thenBy { it }).map { items[it] }
    }

    /**
     * [items] in driving order along the complete [lap]; [gateOf] is the gate of an item. A lap that
     * does not cross every gate (it was driven wide, or the list is for another track) cannot say
     * where the others lie, and guessing would break a list that is right, so then [items] come back
     * unchanged.
     */
    fun <T> orderAlongLap(items: List<T>, lap: List<GeoFix>, gateOf: (T) -> Gate): List<T> {
        val positions = items.map { positionAlongLapM(gateOf(it), lap) }
        if (positions.any { it == null }) return items
        return sortedByPosition(items.indices.toList()) { positions[it] }.map { items[it] }
    }
}
