package nl.stijnvdpol.klinedash.core.geo

/**
 * What the lap timer shows right now.
 *
 * @property isTiming true from the first start/finish crossing on (again from the next crossing after
 *   the location source changed, see [LapDetector.startNewSegment]).
 * @property currentLapElapsedMs time since the current lap started, up to the latest fix.
 * @property currentSplitsMs elapsed time at each sector gate already crossed in the current lap.
 * @property lapCount completed laps, valid or not.
 * @property bestLap fastest valid lap.
 * @property deltaToBestMs current lap minus the best lap at the same distance into the lap
 *   (negative = ahead); null without a best lap or when the current lap is longer than it.
 * @property predictedLapMs best lap time plus [deltaToBestMs].
 * @property referenceLap the fixes of the latest valid complete lap, from the fix before its start
 *   crossing to the fix after its end crossing; empty before the first one. Where a sector gate lies
 *   along the lap, see [SectorOrder].
 */
data class LapTimingState(
    val isTiming: Boolean,
    val currentLapElapsedMs: Long?,
    val currentSplitsMs: List<Long>,
    val lastLap: Lap?,
    val bestLap: Lap?,
    val lapCount: Int,
    val deltaToBestMs: Long?,
    val predictedLapMs: Long?,
    val referenceLap: List<GeoFix> = emptyList(),
)
