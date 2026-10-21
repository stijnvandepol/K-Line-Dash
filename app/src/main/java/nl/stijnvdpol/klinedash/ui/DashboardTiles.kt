package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.core.obd.Signals

internal const val RPM_ID = "rpm"
internal const val SPEED_ID = "speed"

/** Tile order of the wireframe: coolant, intake, throttle, load, timing, battery. */
private val DefaultTileIds = listOf("coolant", "intake_temp", "throttle", "load", "timing", "battery")

/** Fill-ins, in this order, for default tiles the car does not support. */
private val FallbackTileIds = listOf("maf", "stft_b1", "ltft_b1")

/**
 * Which signals the six dashboard tiles show. While the supported PIDs are unknown the defaults are
 * shown (empty); once known, unsupported ones drop out and fallbacks take their place.
 */
internal fun dashboardTileIds(supportedPids: Set<Int>?): List<String> {
    if (supportedPids == null) return DefaultTileIds
    return (DefaultTileIds + FallbackTileIds)
        .filter { id -> Signals.byId(id)?.isAvailable(supportedPids) == true }
        .take(DefaultTileIds.size)
}

/** Everything the dashboard needs from the poller: the gauge signals and the tiles. */
internal fun dashboardDemandIds(tileIds: List<String>): Set<String> = tileIds.toSet() + RPM_ID + SPEED_ID
