package nl.stijnvdpol.klinedash.service

import android.content.res.Resources
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import java.util.Locale
import kotlin.math.ceil

private val DutchLocale: Locale = Locale.forLanguageTag("nl-NL")

/** "13,9" - Dutch decimal comma regardless of the head unit's (often Chinese) system locale. */
fun formatVoltage(volts: Double): String = String.format(DutchLocale, "%.1f", volts)

/** One-line Dutch description, shared by the notification and the UI. */
fun ConnectionState.describe(res: Resources): String = when (this) {
    ConnectionState.Idle -> res.getString(R.string.state_idle)
    is ConnectionState.Connecting -> res.getString(R.string.state_connecting, target)
    is ConnectionState.Initializing -> res.getString(R.string.state_initializing, step)
    is ConnectionState.Connected -> {
        val volts = info.voltage
        if (volts != null) {
            res.getString(R.string.state_connected_voltage, info.protocolName, formatVoltage(volts))
        } else {
            res.getString(R.string.state_connected, info.protocolName)
        }
    }
    is ConnectionState.Reconnecting ->
        res.getString(R.string.state_reconnecting, ceil(delayMs / 1000.0).toInt(), attempt)
    is ConnectionState.Failed -> res.getString(R.string.state_failed, reason)
}
