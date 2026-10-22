package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.data.GridPage
import nl.stijnvdpol.klinedash.data.Page
import nl.stijnvdpol.klinedash.data.signalIds

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

/**
 * Everything the poller must fetch while [page] is the current page: its signals, plus the rpm for
 * the bar on Main and Track. Signals the catalog does not know or the car does not support are left
 * out (a field showing them stays "--"); while the supported PIDs are unknown nothing is left out.
 */
internal fun pageDemandIds(page: Page, supportedPids: Set<Int>?): Set<String> {
    val shown = if (page is GridPage) page.signalIds() else page.signalIds() + RPM_ID
    return shown.filterTo(HashSet()) { id ->
        val signal = Signals.byId(id)
        signal != null && (supportedPids == null || signal.isAvailable(supportedPids))
    }
}

/** On a race display the label of these fields is already the unit ("RPM", "KM/H"). */
internal fun labelIsUnit(signalId: String): Boolean = signalId == RPM_ID || signalId == SPEED_ID
