package nl.stijnvdpol.klinedash.service

import nl.stijnvdpol.klinedash.core.geo.Lap
import nl.stijnvdpol.klinedash.core.geo.LatLon
import nl.stijnvdpol.klinedash.core.obd.SignalIds
import nl.stijnvdpol.klinedash.core.trip.TripSample

/** A lap drawn on the trip detail graph is thinned to at most this many points. */
const val MAX_TRACE_POINTS = 1_500

/** One sample of a lap: [elapsedMs] since the lap's start crossing (GPS time), null where the value is unknown. */
data class TracePoint(val elapsedMs: Long, val speedKmh: Double?, val rpm: Double?)

/**
 * One lap as the trip detail shows it.
 *
 * @property route the driven line of the lap, at most [MAX_ROUTE_POINTS] points.
 * @property points speed and rpm over the lap, at most [MAX_TRACE_POINTS] points, in time order.
 * @property lapTimeMs the lap time, which is where the time axis of [points] ends.
 */
data class LapTrace(val route: List<LatLon>, val points: List<TracePoint>, val lapTimeMs: Long)

/**
 * The samples of [lap] out of a trip: those whose GPS fix lies between the lap's start and end
 * crossing (the lap times are GPS times; the rows' own clock may be set wrongly on a head unit).
 * Speed is the GPS speed where a fix has one and the vehicle speed otherwise, like the trip statistics.
 */
internal fun lapTraceOf(samples: Sequence<TripSample>, lap: Lap): LapTrace {
    val inLap = samples.filter { sample -> sample.gps?.timeMs?.let { it >= lap.startMs && it < lap.endMs } == true }.toList()
    if (inLap.isEmpty()) return LapTrace(emptyList(), emptyList(), lap.lapTimeMs)
    val points = inLap.map { sample ->
        val fix = checkNotNull(sample.gps)
        val speedKmh = fix.speedMps?.times(KMH_PER_MPS) ?: sample.values[SignalIds.SPEED]
        TracePoint(fix.timeMs - lap.startMs, speedKmh, sample.values[SignalIds.RPM])
    }
    return LapTrace(routeOf(inLap.mapNotNull { it.gps }), thinPoints(points), lap.lapTimeMs)
}

/** Every n-th point so that at most [MAX_TRACE_POINTS] remain; the last point is always kept. */
private fun thinPoints(points: List<TracePoint>): List<TracePoint> {
    if (points.size <= MAX_TRACE_POINTS) return points
    val stride = (points.size + MAX_TRACE_POINTS - 2) / (MAX_TRACE_POINTS - 1)
    return points.filterIndexed { index, _ -> index % stride == 0 || index == points.lastIndex }
}
