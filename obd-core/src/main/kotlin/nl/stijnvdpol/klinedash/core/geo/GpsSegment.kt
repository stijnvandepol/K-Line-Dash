package nl.stijnvdpol.klinedash.core.geo

private const val KMH_PER_MPS = 3.6
private const val MS_PER_SECOND = 1_000.0

/**
 * Whether two consecutive fixes may be joined into driven distance. One bad fix (multipath, a first
 * fix far from the truth, the first fix after a tunnel, a switch of location source) would otherwise
 * add the whole jump to a trip or lap, so every consumer that sums fix-to-fix distance asks this
 * first. A rejected pair is a break in the track: nothing is added across it and the newer fix
 * starts the next piece.
 */
object GpsSegment {
    /** Faster than any road car on a track day; a segment implying more is a jump, not driving. */
    const val MAX_PLAUSIBLE_SPEED_KMH = 300.0

    /** A segment may imply at most this many times the speed the car reported for it. */
    const val REPORTED_SPEED_FACTOR = 3.0

    /**
     * The limit from [REPORTED_SPEED_FACTOR] never goes below this, so pulling away from standstill
     * (Doppler still near 0) and the position noise of slow driving are not cut off.
     */
    const val MIN_SPEED_LIMIT_KMH = 30.0

    /** Fixes less accurate than this (horizontal, 68 %) are not used for distance or laps. */
    const val MAX_ACCURACY_M = 20.0

    /** A continuous piece of track shorter than this is a stray fix, see [dropJumps]. */
    const val MIN_PIECE_FIXES = 3

    /** True when [fix] carries no accuracy or one of at most [maxAccuracyM]. */
    fun isAccurate(fix: GeoFix, maxAccuracyM: Double = MAX_ACCURACY_M): Boolean =
        fix.accuracyM == null || fix.accuracyM <= maxAccuracyM

    /**
     * True when driving from [from] to [to] (in this time order) is physically plausible: the implied
     * speed is at most [MAX_PLAUSIBLE_SPEED_KMH] and, when a speed is known for the segment, at most
     * [REPORTED_SPEED_FACTOR] times that speed (but never limited below [MIN_SPEED_LIMIT_KMH]).
     * [reportedSpeedMps] is the speed the car reported for the segment (vehicle speed or Doppler);
     * the Doppler speeds of both fixes are taken into account as well. False when [to] is not newer.
     */
    fun isPlausible(from: GeoFix, to: GeoFix, reportedSpeedMps: Double? = null): Boolean {
        val intervalMs = to.timeMs - from.timeMs
        if (intervalMs <= 0) return false
        val impliedKmh = Geodesy.distanceM(from.position, to.position) / (intervalMs / MS_PER_SECOND) * KMH_PER_MPS
        return impliedKmh <= limitKmh(listOfNotNull(reportedSpeedMps, from.speedMps, to.speedMps).maxOrNull())
    }

    /**
     * [fixes] (time ordered) without the ones that are jumps rather than driving, for drawing a route.
     * Inaccurate fixes ([isAccurate]) and repeats of the same time are dropped. The rest is cut into
     * pieces at every pair that is not plausible, as every distance sum does ([isPlausible]); a piece
     * of fewer than [MIN_PIECE_FIXES] fixes is a stray fix (one bad fix is a piece of one: it breaks
     * the track on both sides) and goes, whether it is in the middle of the drive or the very first
     * fix. What is left is joined again in time order. When no piece is that long the longest one is
     * kept, so a very short trip still has a route.
     */
    fun dropJumps(fixes: Iterable<GeoFix>): List<GeoFix> {
        val pieces = ArrayList<MutableList<GeoFix>>()
        var previous: GeoFix? = null
        for (fix in fixes) {
            if (!isAccurate(fix) || fix.timeMs == previous?.timeMs) continue
            if (previous == null || !isPlausible(previous, fix)) pieces += ArrayList<GeoFix>()
            pieces.last() += fix
            previous = fix
        }
        val kept = pieces.filter { it.size >= MIN_PIECE_FIXES }.ifEmpty { listOfNotNull(pieces.maxByOrNull { it.size }) }
        return kept.flatten()
    }

    private fun limitKmh(referenceMps: Double?): Double {
        if (referenceMps == null || !referenceMps.isFinite()) return MAX_PLAUSIBLE_SPEED_KMH
        val fromReported = maxOf(referenceMps * KMH_PER_MPS * REPORTED_SPEED_FACTOR, MIN_SPEED_LIMIT_KMH)
        return minOf(fromReported, MAX_PLAUSIBLE_SPEED_KMH)
    }
}
