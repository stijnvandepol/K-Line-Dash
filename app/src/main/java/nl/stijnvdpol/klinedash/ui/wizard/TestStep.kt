package nl.stijnvdpol.klinedash.ui.wizard

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.ui.SinglePane
import nl.stijnvdpol.klinedash.core.elm.AdapterInfo
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.transport.TransportKind
import nl.stijnvdpol.klinedash.service.detail
import nl.stijnvdpol.klinedash.service.formatVoltage
import nl.stijnvdpol.klinedash.service.headline
import nl.stijnvdpol.klinedash.transport.needsBluetoothPermissions
import nl.stijnvdpol.klinedash.ui.AdapterField
import nl.stijnvdpol.klinedash.ui.ButtonRow
import nl.stijnvdpol.klinedash.ui.ConnectionViewModel
import nl.stijnvdpol.klinedash.ui.HorizontalRule
import nl.stijnvdpol.klinedash.ui.InfoRow
import nl.stijnvdpol.klinedash.ui.MISSING_VALUE
import nl.stijnvdpol.klinedash.ui.PermissionRequests
import nl.stijnvdpol.klinedash.ui.PrimaryButton
import nl.stijnvdpol.klinedash.ui.SecondaryButton
import nl.stijnvdpol.klinedash.ui.StatusDot
import nl.stijnvdpol.klinedash.ui.StatusDotTitleInset
import nl.stijnvdpol.klinedash.ui.SupportedAdaptersList
import nl.stijnvdpol.klinedash.ui.adapterValues
import nl.stijnvdpol.klinedash.ui.adapterWords
import nl.stijnvdpol.klinedash.ui.openSystemSettings
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

/**
 * Step 4: connects with live progress text, then shows what was found (protocol, adapter, battery,
 * how many sensors the car reports) or exactly what to fix. The test starts by itself when the step
 * opens; "Retry" starts it again and "Choose another adapter" goes back to the adapter step.
 */
@Composable
internal fun TestStep(
    wizard: WizardViewModel,
    connection: ConnectionViewModel,
    permissions: PermissionRequests,
    onChooseAnotherAdapter: () -> Unit,
) {
    val check by wizard.check.collectAsStateWithLifecycle()
    val settings by connection.settings.collectAsStateWithLifecycle()
    val kind = settings?.kind ?: return
    val start = {
        wizard.startCheck(connect = { permissions.connect(kind.needsBluetoothPermissions()) }, stop = connection::disconnect)
    }
    LaunchedEffect(Unit) { if (wizard.check.value is LinkCheck.NotStarted) start() }

    SinglePane {
        StepTitle(stringResource(R.string.wiz_test_title))
        HorizontalRule()
        when (val result = check) {
            LinkCheck.NotStarted, LinkCheck.Starting -> Progress(
                stringResource(R.string.headline_connecting), stringResource(R.string.wiz_test_starting),
            )
            is LinkCheck.Running -> {
                val resources = LocalContext.current.resources
                Progress(result.state.headline(resources), result.state.detail(resources))
            }
            is LinkCheck.Connected -> Success(result, kind)
            is LinkCheck.Failed -> Failure(result, kind, permissions, onRetry = start, onChooseAnotherAdapter)
        }
    }
}

/** Lamp, the state and its detail, as on the Connection page, and how long this may take. */
@Composable
private fun Progress(headline: String, detail: String) {
    val colors = KLineTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical)) {
        StatusDot(colors.caution, Modifier.padding(top = StatusDotTitleInset))
        Spacer(Modifier.width(Spacing.fieldPaddingHorizontal))
        Column {
            Text(headline, style = KLineType.title, color = colors.ink, maxLines = 1)
            Text(detail, style = KLineType.body, color = colors.inkMuted, maxLines = 2)
        }
    }
    HorizontalRule()
    StepText(stringResource(R.string.wiz_test_progress_hint), Modifier.padding(top = Spacing.grid))
}

@Composable
private fun Success(result: LinkCheck.Connected, kind: TransportKind) {
    val colors = KLineTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical)) {
        StatusDot(colors.ok, Modifier.padding(top = StatusDotTitleInset))
        Spacer(Modifier.width(Spacing.fieldPaddingHorizontal))
        Text(stringResource(R.string.wiz_test_ok_title), style = KLineType.title, color = colors.ink, maxLines = 1)
    }
    HorizontalRule()
    val info = result.info
    InfoRow(stringResource(R.string.label_protocol), info.protocolName)
    InfoRow(stringResource(R.string.wiz_test_adapter), adapterName(info))
    InfoRow(stringResource(R.string.wiz_test_battery), info.voltage?.let { stringResource(R.string.wiz_test_volts, formatVoltage(it)) })
    Text(
        text = result.sensorCount?.let { stringResource(R.string.wiz_test_sensors, it.toString()) } ?: stringResource(R.string.wiz_test_sensors_detecting),
        style = KLineType.title,
        color = if (result.sensorCount == null) colors.inkMuted else colors.ink,
        modifier = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingHorizontal),
    )
    if (kind == TransportKind.SIMULATOR) StepText(stringResource(R.string.wiz_test_sim_note))
}

/** The chip family in words ("ELM327 clone"), or the version the adapter reported when the family is not known. */
@Composable
private fun adapterName(info: AdapterInfo): String =
    adapterValues(info, adapterWords())[AdapterField.CHIP] ?: info.elmVersion.ifBlank { MISSING_VALUE }

@Composable
private fun Failure(
    result: LinkCheck.Failed,
    kind: TransportKind,
    permissions: PermissionRequests,
    onRetry: () -> Unit,
    onChooseAnotherAdapter: () -> Unit,
) {
    val colors = KLineTheme.colors
    val advice = linkAdvice(result.cause, kind)
    val context = LocalContext.current
    var isSettingsMissing by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical)) {
        StatusDot(colors.danger, Modifier.padding(top = StatusDotTitleInset))
        Spacer(Modifier.width(Spacing.fieldPaddingHorizontal))
        Text(stringResource(advice.title), style = KLineType.title, color = colors.ink)
    }
    HorizontalRule()
    StepText(stringResource(advice.body), Modifier.padding(top = Spacing.grid), color = colors.ink)
    if (advice.showsAdapterList) SupportedAdaptersList(Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical))
    if (result.cause != LinkFailure.UNSUPPORTED_ADAPTER && result.cause != LinkFailure.PERMISSION_DENIED) {
        Text(
            stringResource(R.string.wiz_test_details, result.reason),
            style = KLineType.caption,
            color = colors.inkMuted,
            modifier = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid / 2),
        )
    }
    if (isSettingsMissing) StepText(stringResource(R.string.wiz_settings_missing), color = colors.caution)
    ButtonRow {
        advice.action?.let { action ->
            SecondaryButton(
                stringResource(action.labelRes),
                onClick = {
                    when (action) {
                        FixAction.ALLOW_BLUETOOTH -> permissions.requestBluetooth()
                        FixAction.OPEN_BLUETOOTH_SETTINGS -> isSettingsMissing = !openSystemSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS)
                        FixAction.OPEN_WIFI_SETTINGS -> isSettingsMissing = !openSystemSettings(context, Settings.ACTION_WIFI_SETTINGS)
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
        if (result.cause != LinkFailure.UNSUPPORTED_ADAPTER) {
            PrimaryButton(stringResource(R.string.wiz_test_retry), onRetry, Modifier.weight(1f))
        }
        SecondaryButton(stringResource(R.string.wiz_test_choose_other), onChooseAnotherAdapter, Modifier.weight(1f))
    }
}
