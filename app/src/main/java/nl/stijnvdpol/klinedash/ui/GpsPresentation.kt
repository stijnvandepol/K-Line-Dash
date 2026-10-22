package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.data.Track
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.service.GpsStatus
import nl.stijnvdpol.klinedash.service.formatNumber

/*
 * What the GPS mark of the bottom bar and the GPS & Tracks page say, as plain functions: the state
 * of the GPS in words, the saved tracks as rows, and the rules for renaming a track.
 */

/** Location is off for the whole device: the GPS page and the gate buttons say the same. */
internal const val LOCATION_OFF_HINT = "Location is switched off on this device. Switch it on in the system settings."

/** Above this ground speed the car counts as driving: the rename field, a keyboard, stays off. */
private const val PARKED_MAX_SPEED_MPS = 1.0

/** Longest track name: it has to fit a row of the trip list. */
internal const val MAX_TRACK_NAME_LENGTH = 60

private const val ACCURACY_DECIMALS = 0
private const val RATE_DECIMALS = 1

/** How the GPS mark in the bottom bar is drawn. */
internal enum class GpsMarkKind { FIX, SEARCHING, OFF }

/** The mark: [satellites] is shown next to "GPS" while there is a fix and the count is known. */
internal data class GpsMark(val kind: GpsMarkKind, val satellites: Int?)

/**
 * Satellites used in the fix, or null when unknown. A fix with zero satellites in use comes from a
 * source without satellite data (a mocked or simulated location, or a device that reports no GNSS
 * status), so "0" would be wrong rather than informative.
 */
private fun knownSatellites(fix: GpsStatus.Fix): Int? = fix.satellites?.takeIf { it > 0 }

internal fun gpsMark(status: GpsStatus): GpsMark = when (status) {
    is GpsStatus.Fix -> GpsMark(GpsMarkKind.FIX, knownSatellites(status))
    GpsStatus.Searching, GpsStatus.Idle -> GpsMark(GpsMarkKind.SEARCHING, null)
    GpsStatus.NoPermission, GpsStatus.Disabled -> GpsMark(GpsMarkKind.OFF, null)
}

/** The GPS status rows of GPS & Tracks; a value that is not known is "--". [hint] says what to do when there is no fix. */
internal data class GpsInfo(val state: String, val satellites: String, val accuracyM: String, val rateHz: String, val hint: String?)

internal fun gpsInfo(status: GpsStatus): GpsInfo {
    val fix = status as? GpsStatus.Fix
    return GpsInfo(
        state = when (status) {
            is GpsStatus.Fix -> "Fix"
            GpsStatus.Searching -> "Searching"
            GpsStatus.Idle -> "Starting"
            GpsStatus.NoPermission -> "Not allowed"
            GpsStatus.Disabled -> "Off"
        },
        satellites = fix?.let(::knownSatellites)?.toString() ?: MISSING_VALUE,
        accuracyM = fix?.accuracyM?.let { formatNumber(it, ACCURACY_DECIMALS) } ?: MISSING_VALUE,
        rateHz = fix?.rateHz?.let { formatNumber(it, RATE_DECIMALS) } ?: MISSING_VALUE,
        hint = when (status) {
            is GpsStatus.Fix -> null
            GpsStatus.Searching -> "No fix yet. A clear view of the sky helps; the first fix can take a minute."
            GpsStatus.Idle -> "The GPS starts when this screen is in front."
            GpsStatus.NoPermission -> "K-Line Dash needs the location permission to time laps and to record where you drive."
            GpsStatus.Disabled -> LOCATION_OFF_HINT
        },
    )
}

/** Renaming opens the keyboard, so it is only offered while the car stands still (or there is no fix to say otherwise). */
internal fun isParked(status: GpsStatus, fix: GeoFix?): Boolean =
    status !is GpsStatus.Fix || (fix?.speedMps ?: 0.0) <= PARKED_MAX_SPEED_MPS

/** One saved track of the list. */
internal data class TrackRowModel(val id: Long, val name: String, val detail: String, val isActive: Boolean)

/** [tracks] newest first; the sessions of a track are the trips recorded on it. */
internal fun trackRows(tracks: List<Track>, active: Track?, trips: List<Trip>): List<TrackRowModel> {
    val sessionsByTrack = trips.groupingBy { it.trackId }.eachCount()
    return tracks.map { track ->
        TrackRowModel(track.id, track.name, trackDetail(track.sectors.size, sessionsByTrack[track.id] ?: 0), track.id == active?.id)
    }
}

private fun trackDetail(sectorLines: Int, sessions: Int): String {
    val lines = when (sectorLines) {
        0 -> "Start/finish line"
        1 -> "Start/finish and 1 sector line"
        else -> "Start/finish and $sectorLines sector lines"
    }
    val sessionText = when (sessions) {
        0 -> "no sessions yet"
        1 -> "1 session"
        else -> "$sessions sessions"
    }
    return "$lines, $sessionText"
}

/** What is wrong with a new track name, or null when it is fine. */
internal fun trackNameProblem(name: String): String? = when {
    name.isBlank() -> "Type a name for the track."
    name.trim().length > MAX_TRACK_NAME_LENGTH -> "Use at most $MAX_TRACK_NAME_LENGTH characters."
    else -> null
}

internal fun deleteTrackQuestion(name: String): String = "Delete track $name? Its trips are kept but lose their laps."
