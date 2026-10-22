package nl.stijnvdpol.klinedash.ui.wizard

import androidx.annotation.StringRes
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.transport.TransportKind

/** A button next to the explanation of a failure, besides "Retry" and "Choose another adapter". */
internal enum class FixAction(@StringRes val labelRes: Int) {
    ALLOW_BLUETOOTH(R.string.fix_allow_bluetooth),
    OPEN_BLUETOOTH_SETTINGS(R.string.btn_open_bt_settings),
    OPEN_WIFI_SETTINGS(R.string.btn_open_wifi_settings),
}

/**
 * What to tell the user about a failed connection: a [title], the [body] with the specific fix, an
 * optional [action] that does the fix, and whether the list of adapters that work belongs under it.
 */
internal data class LinkAdvice(
    @StringRes val title: Int,
    @StringRes val body: Int,
    val action: FixAction? = null,
    val showsAdapterList: Boolean = false,
)

/** The advice for [cause] on an adapter of [kind]; only the "not reached" case depends on the kind. */
internal fun linkAdvice(cause: LinkFailure, kind: TransportKind): LinkAdvice = when (cause) {
    LinkFailure.UNSUPPORTED_ADAPTER ->
        LinkAdvice(R.string.fail_unsupported_title, R.string.fail_unsupported_body, showsAdapterList = true)
    LinkFailure.ECU_NOT_RESPONDING -> LinkAdvice(R.string.fail_ecu_title, R.string.fail_ecu_body)
    LinkFailure.PERMISSION_DENIED ->
        LinkAdvice(R.string.fail_permission_title, R.string.fail_permission_body, FixAction.ALLOW_BLUETOOTH)
    LinkFailure.NO_ADAPTER_CHOSEN -> noAdapterAdvice(kind)
    LinkFailure.BLUETOOTH_OFF ->
        LinkAdvice(R.string.fail_bluetooth_off_title, R.string.fail_bluetooth_off_body, FixAction.OPEN_BLUETOOTH_SETTINGS)
    LinkFailure.BLUETOOTH_UNAVAILABLE -> LinkAdvice(R.string.fail_bluetooth_unavailable_title, R.string.fail_bluetooth_unavailable_body)
    LinkFailure.ADAPTER_NOT_REACHED -> notReachedAdvice(kind)
    LinkFailure.ADAPTER_STOPPED_ANSWERING -> LinkAdvice(R.string.fail_stopped_title, R.string.fail_stopped_body)
    LinkFailure.OTHER -> LinkAdvice(R.string.fail_other_title, R.string.fail_other_body)
}

private fun noAdapterAdvice(kind: TransportKind): LinkAdvice = when (kind) {
    TransportKind.BT_CLASSIC ->
        LinkAdvice(R.string.fail_not_paired_title, R.string.fail_not_paired_body, FixAction.OPEN_BLUETOOTH_SETTINGS)
    TransportKind.BLE -> LinkAdvice(R.string.fail_not_found_title, R.string.fail_not_found_ble_body)
    TransportKind.WIFI -> LinkAdvice(R.string.fail_not_found_title, R.string.fail_wifi_body, FixAction.OPEN_WIFI_SETTINGS)
    TransportKind.SIMULATOR -> LinkAdvice(R.string.fail_other_title, R.string.fail_other_body)
}

private fun notReachedAdvice(kind: TransportKind): LinkAdvice = when (kind) {
    TransportKind.BT_CLASSIC ->
        LinkAdvice(R.string.fail_not_reached_title, R.string.fail_not_reached_classic_body, FixAction.OPEN_BLUETOOTH_SETTINGS)
    TransportKind.BLE -> LinkAdvice(R.string.fail_not_reached_title, R.string.fail_not_reached_ble_body)
    TransportKind.WIFI -> LinkAdvice(R.string.fail_not_reached_title, R.string.fail_wifi_body, FixAction.OPEN_WIFI_SETTINGS)
    TransportKind.SIMULATOR -> LinkAdvice(R.string.fail_other_title, R.string.fail_other_body)
}
