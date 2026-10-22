package nl.stijnvdpol.klinedash.ui.trips

import java.util.Locale
import kotlin.math.abs
import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.geo.LapTimingState
import nl.stijnvdpol.klinedash.core.trip.LapStats
import nl.stijnvdpol.klinedash.data.Track
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.service.GatePlacement
import nl.stijnvdpol.klinedash.service.GpsStatus
import nl.stijnvdpol.klinedash.service.formatNumber
import nl.stijnvdpol.klinedash.service.placeGateAt
import nl.stijnvdpol.klinedash.ui.LOCATION_OFF_HINT
import nl.stijnvdpol.klinedash.ui.MISSING_VALUE
import nl.stijnvdpol.klinedash.ui.formatTripStart

/*
 * What the lap timer and the lap table say, as plain functions: times in fixed formats, the delta
 * with its sign, the live lap panel, one row of the lap table and the reason a start/finish line
 * cannot be set. The composables only draw the result.
 */

private const val MS_PER_SECOND = 1_000L
private const val MS_PER_MINUTE = 60_000L
private const val MS_PER_TENTH = 100L
private const val MS_PER_CENTISECOND = 10.0
private const val CENTISECONDS_PER_SECOND = 100L
private const val DEFAULT_TRACK_NAME_PREFIX = "Circuit "

internal const val BLOCK_NO_PERMISSION = "Location is not allowed. Allow it under Setup, GPS & Tracks."
internal const val BLOCK_DISABLED = LOCATION_OFF_HINT
internal const val BLOCK_WAITING = "Waiting for a GPS fix."

/** The answers to the gate buttons of the session panel. */
internal const val START_FINISH_SET = "Start/finish line set. Lap 1 starts when you cross it."
internal const val SECTOR_SET = "Sector line added."
internal const val NO_TRACK = "There is no track yet. Set the start/finish line first."

/** "1:23.456": the time of a finished lap or a sector, to the millisecond. */
internal fun formatLapTime(ms: Long): String {
    val total = ms.coerceAtLeast(0)
    return "%d:%02d.%03d".format(Locale.US, total / MS_PER_MINUTE, total % MS_PER_MINUTE / MS_PER_SECOND, total % MS_PER_SECOND)
}

/** "1:23.4": the running lap. Tenths are cut off, not rounded, so the clock never shows a moment that has not come. */
internal fun formatLiveLapTime(ms: Long): String {
    val total = ms.coerceAtLeast(0)
    return "%d:%02d.%d".format(Locale.US, total / MS_PER_MINUTE, total % MS_PER_MINUTE / MS_PER_SECOND, total % MS_PER_SECOND / MS_PER_TENTH)
}

private fun centiseconds(deltaMs: Long): Long = Math.round(deltaMs / MS_PER_CENTISECOND)

/** "+0.43" (slower than the best lap), "-0.21" (faster), "0.00" for a difference under 5 ms. */
internal fun formatDelta(deltaMs: Long): String {
    val centis = centiseconds(deltaMs)
    val sign = when {
        centis > 0 -> "+"
        centis < 0 -> "-"
        else -> ""
    }
    val magnitude = abs(centis)
    return "%s%d.%02d".format(Locale.US, sign, magnitude / CENTISECONDS_PER_SECOND, magnitude % CENTISECONDS_PER_SECOND)
}

/** Which colour a delta gets: green when ahead of the best lap, red when behind, white when level. */
internal enum class DeltaTone { FASTER, SLOWER, EVEN }

internal fun deltaTone(deltaMs: Long?): DeltaTone {
    val centis = deltaMs?.let(::centiseconds) ?: return DeltaTone.EVEN
    return when {
        centis < 0 -> DeltaTone.FASTER
        centis > 0 -> DeltaTone.SLOWER
        else -> DeltaTone.EVEN
    }
}

/** A sector gate crossed in the current lap: "S1" and the elapsed time at the gate, or "--" while it is ahead. */
internal data class SectorSplit(val label: String, val time: String)

/** "S1" for the first sector: the name of sector [index] (from 0) in the lap timer and the trip detail. */
internal fun sectorLabel(index: Int): String = "S${index + 1}"

/**
 * The session column is 480 dp wide: three splits fit on a line at body size. More than
 * [FULL_SIZE_SPLITS_MAX] go in the compact form, caption size with [COMPACT_SPLITS_PER_ROW] to a line.
 */
internal const val FULL_SIZE_SPLITS_MAX = 3
internal const val COMPACT_SPLITS_PER_ROW = 4

/** The sector splits as lines of at most [perLine] cells; every cell has the same width, so the columns line up. */
internal data class SplitLines(val isCompact: Boolean, val perLine: Int, val lines: List<List<SectorSplit>>)

/** Splits over the lines of the panel: one line up to [FULL_SIZE_SPLITS_MAX], else compact lines that wrap, so none is cut off. */
internal fun splitLines(splits: List<SectorSplit>): SplitLines {
    val isCompact = splits.size > FULL_SIZE_SPLITS_MAX
    val perLine = if (isCompact) COMPACT_SPLITS_PER_ROW else FULL_SIZE_SPLITS_MAX
    return SplitLines(isCompact, perLine, splits.chunked(perLine))
}

/**
 * The sector times of a lap in the trip detail, which has 750 dp: [DETAIL_SPLITS_PER_LINE] to a line
 * at body size, wrapping, never compact.
 */
internal const val DETAIL_SPLITS_PER_LINE = 4

internal fun detailSplitLines(splits: List<SectorSplit>): SplitLines =
    SplitLines(isCompact = false, perLine = DETAIL_SPLITS_PER_LINE, lines = splits.chunked(DETAIL_SPLITS_PER_LINE))

/** The lap timer as text. [lapCount] counts completed laps. */
internal data class LapPanel(
    val current: String,
    val last: String,
    val best: String,
    val delta: String,
    val deltaTone: DeltaTone,
    val lapCount: Int,
    val splits: List<SectorSplit>,
)

/** The lap timer of the running trip; [sectorGateCount] sector gates of the track give that many splits. */
internal fun lapPanel(timing: LapTimingState?, sectorGateCount: Int): LapPanel {
    val elapsed = timing?.takeIf { it.isTiming }?.currentLapElapsedMs
    val splitsMs = timing?.currentSplitsMs.orEmpty()
    return LapPanel(
        current = elapsed?.let(::formatLiveLapTime) ?: MISSING_VALUE,
        last = timing?.lastLap?.let { formatLapTime(it.lapTimeMs) } ?: MISSING_VALUE,
        best = timing?.bestLap?.let { formatLapTime(it.lapTimeMs) } ?: MISSING_VALUE,
        delta = timing?.deltaToBestMs?.let(::formatDelta) ?: MISSING_VALUE,
        deltaTone = deltaTone(timing?.deltaToBestMs),
        lapCount = timing?.lapCount ?: 0,
        splits = List(sectorGateCount) { index ->
            SectorSplit(sectorLabel(index), splitsMs.getOrNull(index)?.let(::formatLiveLapTime) ?: MISSING_VALUE)
        },
    )
}

/**
 * Why "Set start/finish here" cannot work right now, or null when it can. Without a fix the reason
 * comes from the GPS status; with one, [placeGateAt] says what is wrong with it, so this text and
 * the refusal after a tap are the same sentence.
 */
internal fun gateBlockReason(status: GpsStatus, fix: GeoFix?): String? = when (status) {
    GpsStatus.NoPermission -> BLOCK_NO_PERMISSION
    GpsStatus.Disabled -> BLOCK_DISABLED
    GpsStatus.Idle, GpsStatus.Searching -> BLOCK_WAITING
    is GpsStatus.Fix -> (placeGateAt(fix) as? GatePlacement.Rejected)?.reason
}

/** Name of a track made by "Set start/finish here"; the user renames it under Setup, GPS & Tracks. */
internal fun defaultTrackName(nowMs: Long): String = DEFAULT_TRACK_NAME_PREFIX + formatTripStart(nowMs)

/**
 * The track the lap session starts on: the active one, else the one the newest lap trip used, else
 * null ("Choose a track"). [trips] are newest first.
 */
internal fun lastUsedTrack(active: Track?, trips: List<Trip>, tracks: List<Track>): Track? =
    active ?: trips.firstNotNullOfOrNull { trip -> trip.trackId?.let { id -> tracks.firstOrNull { it.id == id } } }

/** The fastest lap without a GPS dropout; null when there is none. */
internal fun bestLapOf(laps: List<LapStats>): LapStats? = laps.filter { it.lap.isValid }.minByOrNull { it.lap.lapTimeMs }

/** Marks in the lap table: the best lap is green text, a lap with a GPS dropout is not trusted. */
internal enum class LapMark { NONE, BEST, INVALID }

/** One row of the lap table, every value formatted. */
internal data class LapRow(
    val number: Int,
    val time: String,
    val delta: String,
    val maxSpeed: String,
    val maxRpm: String,
    val fullThrottle: String,
    val mark: LapMark,
    val sectors: List<SectorSplit>,
)

/**
 * The lap table. The best lap is the fastest valid one; every other valid lap shows its difference
 * to it, the best lap and the laps with a GPS dropout show "--". Full throttle is the share of the
 * lap with the throttle above 90 %, "--" when the lap has no OBD data (a phone without an adapter).
 */
internal fun lapRows(laps: List<LapStats>): List<LapRow> {
    val best = bestLapOf(laps)
    return laps.map { stats ->
        val lap = stats.lap
        val mark = when {
            !lap.isValid -> LapMark.INVALID
            stats === best -> LapMark.BEST
            else -> LapMark.NONE
        }
        LapRow(
            number = lap.number,
            time = formatLapTime(lap.lapTimeMs),
            delta = if (mark == LapMark.NONE && best != null) formatDelta(lap.lapTimeMs - best.lap.lapTimeMs) else MISSING_VALUE,
            maxSpeed = stats.maxSpeedKmh?.let { formatNumber(it, 0) } ?: MISSING_VALUE,
            maxRpm = stats.maxRpm?.let { formatNumber(it, 0) } ?: MISSING_VALUE,
            fullThrottle = fullThrottleShare(stats.fullThrottleTimeMs, lap.lapTimeMs, hasObdData = stats.maxRpm != null)
                ?.let { formatNumber(it, 0) } ?: MISSING_VALUE,
            mark = mark,
            sectors = lap.sectorTimesMs.mapIndexed { index, timeMs -> SectorSplit(sectorLabel(index), formatLapTime(timeMs)) },
        )
    }
}

/** Why the trip has no laps: no track was set, or none was completed on it. */
internal fun noLapsText(track: Track?): String =
    if (track == null) "No track was set for this trip, so there are no laps." else "No lap was completed on ${track.name}."
