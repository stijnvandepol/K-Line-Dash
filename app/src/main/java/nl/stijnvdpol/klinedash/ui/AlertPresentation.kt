package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.core.alert.Alert
import nl.stijnvdpol.klinedash.core.alert.AlertKind
import nl.stijnvdpol.klinedash.core.alert.AlertSeverity
import nl.stijnvdpol.klinedash.data.UnitSystem
import nl.stijnvdpol.klinedash.service.Quantity
import nl.stijnvdpol.klinedash.service.format
import nl.stijnvdpol.klinedash.service.formatNumber
import nl.stijnvdpol.klinedash.service.label

/*
 * What the warnings overlay shows, as plain functions: the colour of the frame, which alert the
 * banner names and in which words. The composables only draw the result.
 */

private const val COOLANT_LABEL = "Water"
private const val BATTERY_LABEL = "Battery"
private const val VOLTAGE_UNIT = "V"
private const val NEW_DTC_TEXT = "New fault code: check DIAG"
private const val MIL_ON_TEXT = "Check engine light on"
private const val CONNECTION_LOST_TEXT = "Adapter connection lost"
private const val COOLANT_DECIMALS = 0
private const val VOLTAGE_DECIMALS = 1

/** A hidden banner: remembers which occurrence of the alert (by its start) and how severe it was. */
internal data class Dismissal(val sinceMs: Long, val severity: AlertSeverity)

internal typealias Dismissals = Map<AlertKind, Dismissal>

/** The banner to show: the alert that leads and how many more are active behind it. */
internal data class Banner(val alert: Alert, val moreCount: Int)

/** Everything the overlay draws: the frame colour (null = no frame) and the banner (null = none). */
internal data class AlertOverlay(val frame: AlertSeverity? = null, val banner: Banner? = null)

/** Most severe first, then the one that has been active longest; the kind only makes the order total. */
internal fun orderAlerts(alerts: List<Alert>): List<Alert> =
    alerts.sortedWith(compareByDescending<Alert> { it.severity }.thenBy { it.sinceMs }.thenBy { it.kind.ordinal })

/**
 * A tap hides the banner of that occurrence. A new occurrence (the alert cleared and came back, so
 * it has a new start) or a more severe one (caution became danger) shows again.
 */
internal fun isDismissed(alert: Alert, dismissals: Dismissals): Boolean {
    val dismissal = dismissals[alert.kind] ?: return false
    return dismissal.sinceMs == alert.sinceMs && alert.severity <= dismissal.severity
}

internal fun withDismissed(dismissals: Dismissals, alert: Alert): Dismissals =
    dismissals + (alert.kind to Dismissal(alert.sinceMs, alert.severity))

/** The frame stays while any alert is active, hidden banners included. */
internal fun alertOverlay(alerts: List<Alert>, dismissals: Dismissals): AlertOverlay {
    val visible = orderAlerts(alerts).filterNot { isDismissed(it, dismissals) }
    val banner = visible.firstOrNull()?.let { Banner(it, visible.size - 1) }
    return AlertOverlay(frame = alerts.maxOfOrNull { it.severity }, banner = banner)
}

/** "WATER 106 °C" (or "223 °F" in [units]), "BATTERY 11.4 V", ...; a reading that is missing is left out instead of invented. */
internal fun bannerText(alert: Alert, units: UnitSystem): String = when (alert.kind) {
    AlertKind.COOLANT_HIGH -> withValue(
        COOLANT_LABEL, alert.value?.let { units.format(Quantity.TEMPERATURE, it, COOLANT_DECIMALS) }, units.label(Quantity.TEMPERATURE),
    )
    AlertKind.VOLTAGE_LOW, AlertKind.VOLTAGE_HIGH ->
        withValue(BATTERY_LABEL, alert.value?.let { formatNumber(it, VOLTAGE_DECIMALS) }, VOLTAGE_UNIT)
    AlertKind.NEW_DTC -> NEW_DTC_TEXT
    AlertKind.MIL_ON -> MIL_ON_TEXT
    AlertKind.CONNECTION_LOST -> CONNECTION_LOST_TEXT
}.uppercase()

private fun withValue(label: String, value: String?, unit: String): String =
    if (value == null) label else "$label $value $unit"
