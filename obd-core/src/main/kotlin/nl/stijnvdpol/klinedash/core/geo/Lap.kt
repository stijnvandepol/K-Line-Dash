package nl.stijnvdpol.klinedash.core.geo

/** The timing lines of a track: one start/finish gate and optional sector gates in driving order. */
data class LapGates(val startFinish: Gate, val sectors: List<Gate> = emptyList())

/**
 * One completed lap, from one start/finish crossing to the next.
 *
 * @property number 1-based, counted per detector run (the drive before the first crossing is no lap).
 * @property startMs interpolated time of the start crossing, UTC epoch milliseconds.
 * @property sectorTimesMs one duration per sector (sector gates + 1 entries, summing to [lapTimeMs]);
 *   empty when there are no sector gates or one of them was missed.
 * @property distanceM driven distance along the fixes.
 * @property isValid false when the GPS dropped out during the lap, so the time is not trustworthy.
 */
data class Lap(
    val number: Int,
    val startMs: Long,
    val endMs: Long,
    val sectorTimesMs: List<Long>,
    val distanceM: Double,
    val isValid: Boolean,
) {
    val lapTimeMs: Long get() = endMs - startMs
}

/** Finds the lap a point in time belongs to; laps must be in time order, as a detector emits them. */
class LapIndex(private val laps: List<Lap>) {

    /** The number of the lap that contains [timeMs] (start inclusive, end exclusive), or null. */
    fun numberAt(timeMs: Long): Int? {
        var low = 0
        var high = laps.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val lap = laps[middle]
            when {
                timeMs < lap.startMs -> high = middle - 1
                timeMs >= lap.endMs -> low = middle + 1
                else -> return lap.number
            }
        }
        return null
    }
}
