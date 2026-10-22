package nl.stijnvdpol.klinedash.service

import android.content.res.Resources
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.elm.FailureKind
import kotlin.math.ceil

private const val MILLIS_PER_SECOND = 1000.0

/** One-line description, shared by the notification and the UI. */
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
        res.getString(R.string.state_reconnecting, ceil(delayMs / MILLIS_PER_SECOND).toInt(), attempt)
    is ConnectionState.Failed -> res.getString(R.string.state_failed, reason)
}

/** Short state name for a single line, e.g. "Connected". Details come from [detail]. */
fun ConnectionState.headline(res: Resources): String = res.getString(
    when (this) {
        ConnectionState.Idle -> R.string.state_idle
        is ConnectionState.Connecting -> R.string.headline_connecting
        is ConnectionState.Initializing -> R.string.headline_initializing
        is ConnectionState.Connected -> R.string.headline_connected
        is ConnectionState.Reconnecting -> R.string.headline_reconnecting
        is ConnectionState.Failed ->
            if (kind == FailureKind.UNSUPPORTED_ADAPTER) R.string.headline_unsupported_adapter else R.string.headline_failed
    },
)

/** Second, muted line under [headline]: protocol and voltage, the target, or what to do next. */
fun ConnectionState.detail(res: Resources): String = when (this) {
    ConnectionState.Idle -> res.getString(R.string.detail_idle)
    is ConnectionState.Connecting -> res.getString(R.string.detail_connecting, target)
    is ConnectionState.Initializing -> step
    is ConnectionState.Connected -> {
        val volts = info.voltage
        if (volts != null) {
            res.getString(R.string.detail_connected_voltage, info.protocolName, formatVoltage(volts))
        } else {
            info.protocolName
        }
    }
    is ConnectionState.Reconnecting ->
        res.getString(R.string.detail_reconnecting, attempt, ceil(delayMs / MILLIS_PER_SECOND).toInt())
    // Retrying cannot help with an unsupported adapter, so no "tap Connect" advice.
    is ConnectionState.Failed ->
        if (kind == FailureKind.UNSUPPORTED_ADAPTER) reason else res.getString(R.string.detail_failed, reason.trimEnd('.'))
}
