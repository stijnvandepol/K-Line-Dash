package nl.stijnvdpol.klinedash.ui

import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.transport.BleScanNotice
import nl.stijnvdpol.klinedash.transport.ScanBlock
import nl.stijnvdpol.klinedash.transport.ScanCheck
import nl.stijnvdpol.klinedash.transport.emptyScanChecks
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private const val CHECK_BULLET = "– "

/**
 * What the device list says besides the devices after a scan that did not run or found nothing: the
 * reason and, for Location, the button that opens the setting; or the checklist for an empty result.
 * Shared by SETUP > Connection and the setup wizard. Draws nothing without a [notice].
 */
@Composable
internal fun BleScanNoticeBlock(notice: BleScanNotice?) {
    when (notice) {
        null -> Unit
        is BleScanNotice.Blocked -> BlockedNotice(notice.block)
        is BleScanNotice.NothingFound -> NothingFoundNotice(notice.failureCode)
    }
}

@Composable
private fun BlockedNotice(block: ScanBlock) {
    NoticeLine(stringResource(block.messageRes(), *block.messageArguments()), KLineTheme.colors.caution)
    if (block == ScanBlock.LocationOff) LocationSettingsButton()
}

@Composable
private fun LocationSettingsButton() {
    val context = LocalContext.current
    var isSettingsMissing by remember { mutableStateOf(false) }
    ButtonRow {
        SecondaryButton(
            stringResource(R.string.scan_open_location_settings),
            onClick = { isSettingsMissing = !openSystemSettings(context, Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
            modifier = Modifier.weight(1f),
        )
    }
    if (isSettingsMissing) Hint(stringResource(R.string.wiz_settings_missing))
}

@Composable
private fun NothingFoundNotice(failureCode: Int?) {
    val colors = KLineTheme.colors
    HorizontalRule()
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.fieldPaddingVertical)) {
        // A failure code is the stack's own answer, so it comes first; the checklist is for the silent cases.
        if (failureCode != null) {
            Text(
                stringResource(R.string.scan_failed_code, failureCode.toString()),
                style = KLineType.body,
                color = colors.caution,
                modifier = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal).padding(bottom = Spacing.fieldPaddingVertical),
            )
        }
        Text(
            stringResource(R.string.scan_nothing_found),
            style = KLineType.body,
            color = colors.ink,
            modifier = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal),
        )
        emptyScanChecks(Build.VERSION.SDK_INT).forEach { check ->
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
private fun ScanBlock.messageRes(): Int = when (this) {
    ScanBlock.NoAdapter -> R.string.scan_blocked_no_adapter
    ScanBlock.BluetoothOff -> R.string.scan_blocked_bluetooth_off
    ScanBlock.NoLeScanner -> R.string.scan_blocked_no_ble
    ScanBlock.LocationOff -> R.string.scan_blocked_location_off
    is ScanBlock.Throttled -> R.string.scan_blocked_throttled
}

/** Numbers go into the text as strings, so the display never formats them in the system locale. */
private fun ScanBlock.messageArguments(): Array<Any> = when (this) {
    is ScanBlock.Throttled -> arrayOf(waitSeconds.toString())
    else -> emptyArray()
}

@StringRes
private fun ScanCheck.labelRes(): Int = when (this) {
    ScanCheck.LOCATION_ON -> R.string.scan_check_location
    ScanCheck.ADAPTER_POWERED -> R.string.scan_check_power
    ScanCheck.NOT_CONNECTED_ELSEWHERE -> R.string.scan_check_free
    ScanCheck.WAIT_BETWEEN_SCANS -> R.string.scan_check_wait
    ScanCheck.LOOK_IN_ANDROID_SETTINGS -> R.string.scan_check_settings
}
