package nl.stijnvdpol.klinedash.ui.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.obd.Signal
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.data.WidgetType
import nl.stijnvdpol.klinedash.ui.ChoiceCells
import nl.stijnvdpol.klinedash.ui.DialogListMaxHeight
import nl.stijnvdpol.klinedash.ui.HorizontalRule
import nl.stijnvdpol.klinedash.ui.LocalUnitSystem
import nl.stijnvdpol.klinedash.ui.RaceDialog
import nl.stijnvdpol.klinedash.ui.SecondaryButton
import nl.stijnvdpol.klinedash.ui.SelectableRow
import nl.stijnvdpol.klinedash.ui.rememberInitialFocus
import nl.stijnvdpol.klinedash.ui.unitOf
import nl.stijnvdpol.klinedash.ui.supportedSignals
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

/** The signals the dashboard can show: those the car supports, or all of them while that is not known. */
internal fun pickableSignals(supportedPids: Set<Int>?): List<Signal> =
    if (supportedPids == null) Signals.all else supportedSignals(supportedPids)

/**
 * Picks the signal of one cell. A tap on a signal chooses it and closes the dialog. For a grid cell
 * [widget] is the current widget type and [onWidget] changes it at once (the dialog stays open, so
 * both can be changed in one visit).
 */
@Composable
internal fun SignalPickerDialog(
    currentSignalId: String,
    signals: List<Signal>,
    isCarKnown: Boolean,
    widget: WidgetType?,
    onWidget: (WidgetType) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = rememberInitialFocus()
    RaceDialog(
        title = stringResource(R.string.dash_pick_title),
        onDismiss = onDismiss,
        actions = { SecondaryButton(stringResource(R.string.btn_cancel), onDismiss, Modifier.weight(1f).focusRequester(focus)) },
        modifier = Modifier.padding(vertical = Spacing.fieldPaddingHorizontal),
    ) {
        HorizontalRule()
        if (widget != null) {
            val types = WidgetType.entries
            ChoiceCells(
                labels = types.map { stringResource(widgetLabelRes(it)) },
                selectedIndex = types.indexOf(widget),
                onSelect = { onWidget(types[it]) },
            )
            HorizontalRule()
        }
        if (!isCarKnown) {
            Text(
                stringResource(R.string.dash_pick_not_connected),
                style = KLineType.caption,
                color = KLineTheme.colors.inkMuted,
                modifier = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid),
            )
            HorizontalRule()
        }
        // Takes what the buttons and the choice above leave, so a short screen scrolls the list, not the dialog.
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = DialogListMaxHeight).verticalScroll(rememberScrollState())) {
            signals.forEach { signal ->
                SelectableRow(selected = signal.id == currentSignalId, onClick = { onPick(signal.id) }) {
                    Text(signal.name, style = KLineType.body, color = KLineTheme.colors.ink, modifier = Modifier.weight(1f))
                    Text(unitOf(signal, LocalUnitSystem.current), style = KLineType.caption, color = KLineTheme.colors.inkMuted)
                }
            }
        }
    }
}
