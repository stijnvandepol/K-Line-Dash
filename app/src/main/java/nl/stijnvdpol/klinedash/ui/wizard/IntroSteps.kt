package nl.stijnvdpol.klinedash.ui.wizard

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
import nl.stijnvdpol.klinedash.ui.SinglePane
import nl.stijnvdpol.klinedash.core.transport.TransportKind
import nl.stijnvdpol.klinedash.ui.ConnectionViewModel
import nl.stijnvdpol.klinedash.ui.HorizontalRule
import nl.stijnvdpol.klinedash.ui.SelectableRow
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

/** What the first screen tells: the app, in three short lines, and the promise about data. */
@Composable
internal fun WelcomeStep() {
    val colors = KLineTheme.colors
    SinglePane {
        Text(
            stringResource(R.string.app_name).uppercase(),
            style = KLineType.fieldValue,
            color = colors.ink,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal).padding(top = Spacing.fieldPaddingHorizontal),
        )
        StepTitle(stringResource(R.string.wiz_welcome_title))
        StepText(stringResource(R.string.wiz_welcome_line_live))
        StepText(stringResource(R.string.wiz_welcome_line_adapter))
        StepText(stringResource(R.string.wiz_welcome_line_time))
        HorizontalRule(Modifier.padding(top = Spacing.fieldPaddingHorizontal))
        StepText(stringResource(R.string.wiz_welcome_privacy), Modifier.padding(vertical = Spacing.fieldPaddingVertical), color = colors.ink)
        HorizontalRule()
    }
}

private class AdapterChoice(val kind: TransportKind, @StringRes val labelRes: Int, @StringRes val hintRes: Int)

/** The four ways in, the most common first; the simulator is last and says what it is for. */
private val AdapterChoices = listOf(
    AdapterChoice(TransportKind.BT_CLASSIC, R.string.wiz_adapter_bt, R.string.wiz_adapter_bt_hint),
    AdapterChoice(TransportKind.BLE, R.string.wiz_adapter_ble, R.string.wiz_adapter_ble_hint),
    AdapterChoice(TransportKind.WIFI, R.string.wiz_adapter_wifi, R.string.wiz_adapter_wifi_hint),
    AdapterChoice(TransportKind.SIMULATOR, R.string.wiz_adapter_sim, R.string.wiz_adapter_sim_hint),
)

/** Which kind of adapter the user has; the choice is stored at once, as on SETUP > Connection. */
@Composable
internal fun AdapterStep(connection: ConnectionViewModel) {
    val settings by connection.settings.collectAsStateWithLifecycle()
    val chosen = settings?.kind
    val colors = KLineTheme.colors
    SinglePane {
        StepTitle(stringResource(R.string.wiz_adapter_title))
        HorizontalRule()
        AdapterChoices.forEach { choice ->
            SelectableRow(selected = choice.kind == chosen, onClick = { connection.selectKind(choice.kind) }) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(choice.labelRes),
                        style = KLineType.body,
                        color = if (choice.kind == chosen) colors.ink else colors.inkMuted,
                    )
                    Text(stringResource(choice.hintRes), style = KLineType.caption, color = colors.inkMuted)
                }
            }
        }
        StepText(stringResource(R.string.wiz_adapter_tip), Modifier.padding(top = Spacing.fieldPaddingVertical))
    }
}

/** The last screen: where to look next. */
@Composable
internal fun DoneStep() {
    SinglePane {
        StepTitle(stringResource(R.string.wiz_done_title))
        HorizontalRule()
        DoneLine(R.string.wiz_done_dash_label, R.string.wiz_done_dash)
        DoneLine(R.string.wiz_done_sensors_label, R.string.wiz_done_sensors)
        DoneLine(R.string.wiz_done_help_label, R.string.wiz_done_help)
    }
}

@Composable
private fun DoneLine(@StringRes labelRes: Int, @StringRes textRes: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical)) {
        Text(stringResource(labelRes).uppercase(), style = KLineType.fieldLabel, color = KLineTheme.colors.inkMuted)
        Text(stringResource(textRes), style = KLineType.body, color = KLineTheme.colors.ink)
    }
    HorizontalRule()
}
