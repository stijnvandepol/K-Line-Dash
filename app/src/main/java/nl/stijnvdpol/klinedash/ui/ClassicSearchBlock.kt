package nl.stijnvdpol.klinedash.ui

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.transport.BleScanNotice
import nl.stijnvdpol.klinedash.transport.ClassicPairing
import nl.stijnvdpol.klinedash.transport.ClassicSearchCheck
import nl.stijnvdpol.klinedash.transport.PairingFailure
import nl.stijnvdpol.klinedash.transport.classicSearchChecks
import nl.stijnvdpol.klinedash.transport.suggestsPairingAgain
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

/** The Search button of the Bluetooth Classic section; it says "Searching…" and waits while a search runs. */
@Composable
internal fun ClassicSearchButton(connection: ConnectionViewModel, onSearch: () -> Unit) {
    val isSearching by connection.classicSearching.collectAsStateWithLifecycle()
    val pairing by connection.pairing.collectAsStateWithLifecycle()
    ButtonRow {
        SecondaryButton(
            stringResource(if (isSearching) R.string.bt_searching else R.string.bt_search),
            onClick = onSearch,
            modifier = Modifier.weight(1f),
            enabled = !isSearching && pairing !is ClassicPairing.Pairing,
        )
    }
}

/**
 * Everything a Bluetooth Classic search leaves on screen: why it did not run or what to check, how the
 * pairing stands, and the devices found (tap one to pair it, or to choose it when it is already paired). Shared by SETUP > Connection and
 * the setup wizard. [onConnect] adds a Connect button to the "paired" message; null leaves it out.
 * [isPermissionGranted] hides a stale "permission missing" notice once the permission is there.
 */
@Composable
internal fun ClassicSearchResults(connection: ConnectionViewModel, isPermissionGranted: Boolean, onConnect: (() -> Unit)?) {
    val found by connection.classicFound.collectAsStateWithLifecycle()
    val isSearching by connection.classicSearching.collectAsStateWithLifecycle()
    val notice by connection.classicNotice.collectAsStateWithLifecycle()
    val pairing by connection.pairing.collectAsStateWithLifecycle()
    val shownNotice = notice.takeUnless { it == BleScanNotice.PermissionMissing && isPermissionGranted }
    BleScanNoticeBlock(shownNotice)
    PairingStatus(pairing, onRetry = connection::retryPairing, onConnect = onConnect)
    DeviceList(
        devices = found,
        selectedAddress = null,
        // A notice or a pairing message already says why the list is empty.
        emptyText = if (shownNotice == null && pairing is ClassicPairing.Idle && !isSearching) stringResource(R.string.bt_search_no_results) else null,
        onSelect = connection::pairWith,
    )
}

@Composable
private fun PairingStatus(pairing: ClassicPairing, onRetry: () -> Unit, onConnect: (() -> Unit)?) {
    when (pairing) {
        ClassicPairing.Idle -> Unit
        is ClassicPairing.Pairing -> {
            NoticeLine(stringResource(R.string.bt_pairing, pairing.device.name), KLineTheme.colors.ink)
            Hint(stringResource(R.string.bt_pairing_pin_hint))
        }
        is ClassicPairing.Paired -> {
            NoticeLine(stringResource(R.string.bt_paired, pairing.device.name), KLineTheme.colors.ink)
            if (onConnect != null) ButtonRow { PrimaryButton(stringResource(R.string.btn_connect), onConnect, Modifier.weight(1f)) }
        }
        is ClassicPairing.Failed -> {
            NoticeLine(stringResource(pairing.reason.messageRes(), pairing.device.name), KLineTheme.colors.caution)
            if (pairing.reason.isAboutPin()) Hint(stringResource(R.string.bt_pairing_pin_hint))
            ButtonRow { SecondaryButton(stringResource(R.string.btn_retry), onRetry, Modifier.weight(1f)) }
        }
    }
}

@StringRes
private fun PairingFailure.messageRes(): Int = when (this) {
    PairingFailure.NOT_STARTED -> R.string.bt_pairing_not_started
    PairingFailure.NO_ADAPTER -> R.string.bt_pairing_no_adapter
    PairingFailure.BLUETOOTH_OFF -> R.string.bt_pairing_bluetooth_off
    PairingFailure.PERMISSION_MISSING -> R.string.bt_pairing_permission_missing
    PairingFailure.UNPAIR_FAILED -> R.string.bt_pairing_unpair_failed
    PairingFailure.REJECTED -> R.string.bt_pairing_rejected
    PairingFailure.UNREACHABLE -> R.string.bt_pairing_unreachable
    PairingFailure.TIMED_OUT -> R.string.bt_pairing_timed_out
}

/** Only these failures can be a matter of the PIN, so only they repeat the PIN hint. */
private fun PairingFailure.isAboutPin(): Boolean = this == PairingFailure.REJECTED || this == PairingFailure.TIMED_OUT

/**
 * Offers to remove the pairing of the selected adapter and pair again, while the link fails in the way
 * of a stale pairing (see [suggestsPairingAgain]). Re-flashing an ESP32 bridge erases its link keys.
 */
@Composable
internal fun PairAgainAction(connection: ConnectionViewModel, btAddress: String?, btName: String?) {
    val state by connection.connectionState.collectAsStateWithLifecycle()
    val pairing by connection.pairing.collectAsStateWithLifecycle()
    if (btAddress == null || !suggestsPairingAgain(state.failureReason())) return
    Hint(stringResource(R.string.bt_pair_again_hint))
    ButtonRow {
        SecondaryButton(
            stringResource(R.string.btn_pair_again),
            onClick = { connection.pairAgain(btAddress, btName) },
            modifier = Modifier.weight(1f),
            enabled = pairing !is ClassicPairing.Pairing,
        )
    }
}

private fun ConnectionState.failureReason(): String? = when (this) {
    is ConnectionState.Reconnecting -> reason
    is ConnectionState.Failed -> reason
    else -> null
}

/** The checklist for a search that found nothing, for [BleScanNoticeBlock]. */
@Composable
internal fun ClassicNothingFoundNotice() {
    val colors = KLineTheme.colors
    HorizontalRule()
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.fieldPaddingVertical)) {
        Text(
            stringResource(R.string.bt_search_nothing_found),
            style = KLineType.body,
            color = colors.ink,
            modifier = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal),
        )
        classicSearchChecks(Build.VERSION.SDK_INT).forEach { check ->
            Text(
                CHECK_BULLET + stringResource(check.labelRes()),
                style = KLineType.body,
                color = colors.inkMuted,
                modifier = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid / 2),
            )
        }
    }
}

@StringRes
private fun ClassicSearchCheck.labelRes(): Int = when (this) {
    ClassicSearchCheck.LOCATION_ON -> R.string.scan_check_location
    ClassicSearchCheck.ADAPTER_POWERED -> R.string.scan_check_power
    ClassicSearchCheck.IN_RANGE -> R.string.bt_check_range
    ClassicSearchCheck.NOT_CONNECTED_ELSEWHERE -> R.string.scan_check_free
    ClassicSearchCheck.BRIDGE_FREE -> R.string.bt_check_bridge
}
