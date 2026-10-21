package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.core.obd.Signals

internal const val RPM_ID = "rpm"
internal const val SPEED_ID = "speed"
internal const val COOLANT_ID = "coolant"
internal const val BATTERY_ID = "battery"
internal const val INTAKE_ID = "intake_temp"
internal const val THROTTLE_ID = "throttle"
internal const val LOAD_ID = "load"
internal const val TIMING_ID = "timing"
internal const val MAF_ID = "maf"
internal const val MIL_ID = "mil_on"

/** Field order of the wireframe: intake, throttle, load, ignition timing, battery, air flow. */
private val DefaultFieldIds = listOf(INTAKE_ID, THROTTLE_ID, LOAD_ID, TIMING_ID, BATTERY_ID, MAF_ID)

/** Fill-ins, in this order, for default fields the car does not support. */
private val FallbackFieldIds = listOf("stft_b1", "ltft_b1")

/**
 * Which signals the six small dashboard fields show. While the supported PIDs are unknown the
 * defaults are shown (empty); once known, unsupported ones drop out and fallbacks take their place.
 */
internal fun dashboardFieldIds(supportedPids: Set<Int>?): List<String> {
    if (supportedPids == null) return DefaultFieldIds
    return (DefaultFieldIds + FallbackFieldIds)
        .filter { id -> Signals.byId(id)?.isAvailable(supportedPids) == true }
        .take(DefaultFieldIds.size)
}

/** Everything the dashboard needs from the poller: the three primary fields and the small ones. */
internal fun dashboardDemandIds(fieldIds: List<String>): Set<String> = fieldIds.toSet() + RPM_ID + SPEED_ID + COOLANT_ID

/** On a race display the label of these fields is already the unit ("RPM", "KM/H"). */
internal fun labelIsUnit(signalId: String): Boolean = signalId == RPM_ID || signalId == SPEED_ID
