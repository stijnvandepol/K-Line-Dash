package nl.stijnvdpol.klinedash.core.geo

/** Elapsed lap time against distance driven in the lap: the reference for a live delta. */
class LapProfile(private val distancesM: DoubleArray, private val elapsedMs: LongArray) {

    /**
     * The elapsed time at which this lap had covered [distanceM], interpolated linearly; null when
     * the lap was shorter than that or [distanceM] is negative.
     */
    fun elapsedAtDistance(distanceM: Double): Long? {
        if (distancesM.isEmpty() || distanceM < 0.0 || distanceM > distancesM.last()) return null
        var low = 0
        var high = distancesM.size - 1
        while (low < high) {
            val middle = (low + high) ushr 1
            if (distancesM[middle] < distanceM) low = middle + 1 else high = middle
        }
        if (low == 0) return elapsedMs[0]
        val spanM = distancesM[low] - distancesM[low - 1]
        if (spanM <= 0.0) return elapsedMs[low]
        val fraction = (distanceM - distancesM[low - 1]) / spanM
        return elapsedMs[low - 1] + Math.round(fraction * (elapsedMs[low] - elapsedMs[low - 1]))
    }
}

/** Collects (distance, elapsed) points while a lap is driven. */
class LapProfileBuilder {
    private val distances = ArrayList<Double>()
    private val elapsed = ArrayList<Long>()

    var distanceM = 0.0
        private set
    var elapsedMs = 0L
        private set

    /** Records that the lap now stands at [totalDistanceM] after [totalElapsedMs]. */
    fun addPoint(totalDistanceM: Double, totalElapsedMs: Long) {
        distanceM = totalDistanceM
        elapsedMs = totalElapsedMs
        distances += totalDistanceM
        elapsed += totalElapsedMs
    }

    fun build(): LapProfile = LapProfile(distances.toDoubleArray(), elapsed.toLongArray())
}
