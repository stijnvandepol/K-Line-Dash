package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

/** The dialog for the step the clear flow is waiting on; nothing while [ClearDialog.NONE]. */
@Composable
internal fun ClearDialogHost(dialog: ClearDialog, vm: DiagnosticsViewModel) {
    when (dialog) {
        ClearDialog.NONE -> Unit
        ClearDialog.ENGINE_RUNNING -> EngineRunningDialog(vm::dismissDialog)
        ClearDialog.INTRO -> ClearIntroDialog(onCancel = vm::dismissDialog, onContinue = vm::continueClear)
        ClearDialog.FINAL -> ClearFinalDialog(onCancel = vm::dismissDialog, onClear = vm::confirmClear)
    }
}

@Composable
private fun EngineRunningDialog(onDismiss: () -> Unit) {
    val focus = rememberInitialFocus()
    RaceDialog(
        title = stringResource(R.string.clear_engine_title),
        onDismiss = onDismiss,
        actions = { PrimaryButton(stringResource(R.string.btn_ok), onDismiss, Modifier.weight(1f).focusRequester(focus)) },
    ) {
        HorizontalRule()
        DialogText(stringResource(R.string.clear_engine_body))
    }
}

@Composable
private fun ClearIntroDialog(onCancel: () -> Unit, onContinue: () -> Unit) {
    val focus = rememberInitialFocus()
    RaceDialog(
        title = stringResource(R.string.clear_intro_title),
        onDismiss = onCancel,
        actions = {
            SecondaryButton(stringResource(R.string.btn_cancel), onCancel, Modifier.weight(1f).focusRequester(focus))
            PrimaryButton(stringResource(R.string.btn_continue), onContinue, Modifier.weight(1f))
        },
    ) {
        HorizontalRule()
        DialogSection(stringResource(R.string.clear_intro_what_label), stringResource(R.string.clear_intro_what))
        HorizontalRule()
        DialogSection(stringResource(R.string.clear_intro_then_label), stringResource(R.string.clear_intro_then))
        HorizontalRule()
        DialogSection(stringResource(R.string.clear_requirements_label), stringResource(R.string.clear_requirements))
    }
}

/** Last chance. Cancel is the filled button and has the focus; the destructive answer is outlined in danger. */
@Composable
private fun ClearFinalDialog(onCancel: () -> Unit, onClear: () -> Unit) {
    val focus = rememberInitialFocus()
    RaceDialog(
        title = stringResource(R.string.clear_final_title),
        onDismiss = onCancel,
        accent = KLineTheme.colors.danger,
        actions = {
            PrimaryButton(stringResource(R.string.btn_cancel), onCancel, Modifier.weight(1f).focusRequester(focus))
            DangerButton(stringResource(R.string.btn_clear_now), onClear, Modifier.weight(1f))
        },
    ) {
        HorizontalRule()
        DialogText(stringResource(R.string.clear_final_body))
        HorizontalRule()
        DialogSection(stringResource(R.string.clear_requirements_label), stringResource(R.string.clear_requirements))
    }
}

/** A caps label over a paragraph, like the labelled fields of the display. */
@Composable
private fun DialogSection(label: String, text: String) {
    Column(Modifier.padding(Spacing.fieldPaddingHorizontal)) {
        Text(label.uppercase(), style = KLineType.fieldLabel, color = KLineTheme.colors.inkMuted)
        Text(text, style = KLineType.body, color = KLineTheme.colors.ink, modifier = Modifier.padding(top = Spacing.grid))
    }
}
