package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.data.ClusterStyle
import nl.stijnvdpol.klinedash.data.GridPage
import nl.stijnvdpol.klinedash.data.MainPage
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

/** What the dials and arcs of the cluster styles draw, whatever the page's own primary fields are. */
private val ClusterSignalIds = listOf(RPM_ID, SPEED_ID, COOLANT_ID, BATTERY_ID)

/**
 * Everything the poller must fetch while [page] is the current page: its signals, plus the rpm for
 * the bar on Main and Track, or what the dials of the [cluster] style show on Main. Signals the
 * catalog does not know or the car does not support are left out (a field showing them stays "--");
 * while the supported PIDs are unknown nothing is left out.
 */
internal fun pageDemandIds(page: Page, supportedPids: Set<Int>?, cluster: ClusterStyle = ClusterStyle.BAR): Set<String> {
    val shown = when {
        page is GridPage -> page.signalIds()
        page is MainPage && cluster != ClusterStyle.BAR -> page.signalIds() + ClusterSignalIds
        else -> page.signalIds() + RPM_ID
    }
    return shown.filterTo(HashSet()) { id ->
        val signal = Signals.byId(id)
        signal != null && (supportedPids == null || signal.isAvailable(supportedPids))
    }
}

/** On a race display the label of these fields is already the unit ("RPM", "KM/H"). */
internal fun labelIsUnit(signalId: String): Boolean = signalId == RPM_ID || signalId == SPEED_ID
