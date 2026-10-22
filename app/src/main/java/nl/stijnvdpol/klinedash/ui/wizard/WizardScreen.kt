package nl.stijnvdpol.klinedash.ui.wizard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.ui.ConnectionViewModel
import nl.stijnvdpol.klinedash.ui.HorizontalRule
import nl.stijnvdpol.klinedash.ui.PermissionRequests
import nl.stijnvdpol.klinedash.ui.PrimaryButton
import nl.stijnvdpol.klinedash.ui.TextActionButton
import nl.stijnvdpol.klinedash.ui.VerticalRule
import nl.stijnvdpol.klinedash.ui.graphViewModel
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.LocalIsKeyboardCrowding
import nl.stijnvdpol.klinedash.ui.theme.LocalScreenProfile
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private val SkipButtonWidth = 200.dp

/**
 * The first-run setup (and SETUP > Help > Run setup again): one screen per step with "Step n of m"
 * and "Skip setup" on top and Back and Next below. It drives the same ViewModels as the SETUP pages
 * (connection, display) and finishes by marking the setup as seen. [onFinished] runs after "Skip
 * setup" and after the last step alike.
 */
@Composable
internal fun SetupWizard(permissions: PermissionRequests, onFinished: () -> Unit) {
    val wizard = graphViewModel { WizardViewModel.forGraph(it) }
    val connection = graphViewModel { ConnectionViewModel(it) }
    val step by wizard.step.collectAsStateWithLifecycle()
    val check by wizard.check.collectAsStateWithLifecycle()
    val settings by connection.settings.collectAsStateWithLifecycle()
    // The next run of the setup starts at the beginning, not where this one stopped.
    DisposableEffect(wizard) { onDispose(wizard::restart) }
    val current = settings ?: return

    val kind = current.kind
    val goBack = { previousStep(step, kind)?.let(wizard::goTo) }
    val goNext = {
        val next = nextStep(step, kind)
        if (next == null) wizard.finish(onFinished) else wizard.goTo(next)
    }
    BackHandler(enabled = previousStep(step, kind) != null) { goBack() }

    // A phone held in landscape has no height to spare: the step count and "Skip setup" join the footer.
    val isChromeMerged = LocalScreenProfile.current.isCompact
    val position = stepPosition(step, kind)
    val skip = { wizard.finish(onFinished) }
    Column(Modifier.fillMaxSize()) {
        if (!isChromeMerged) {
            WizardHeader(position, onSkip = skip)
            HorizontalRule()
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            WizardContent(step, wizard, connection, permissions, onChooseAnotherAdapter = { wizard.goTo(WizardStep.ADAPTER) })
        }
        if (!LocalIsKeyboardCrowding.current) {
            HorizontalRule()
            WizardFooter(
                step = step,
                isFirst = previousStep(step, kind) == null,
                canContinue = canContinue(step, current, check),
                onBack = { goBack() },
                onNext = { goNext() },
                mergedHeader = if (isChromeMerged) MergedHeader(position, skip) else null,
            )
        }
    }
}

@Composable
private fun WizardContent(
    step: WizardStep,
    wizard: WizardViewModel,
    connection: ConnectionViewModel,
    permissions: PermissionRequests,
    onChooseAnotherAdapter: () -> Unit,
) {
    when (step) {
        WizardStep.WELCOME -> WelcomeStep()
        WizardStep.ADAPTER -> AdapterStep(connection)
        WizardStep.CONNECT -> ConnectStep(connection, permissions)
        WizardStep.TEST -> TestStep(wizard, connection, permissions, onChooseAnotherAdapter)
        WizardStep.PREFERENCES -> PreferencesStep(wizard, permissions)
        WizardStep.DONE -> DoneStep()
    }
}

/** "Step 3 of 6" at the left and "Skip setup" at the right, one 72 dp row. */
@Composable
private fun WizardHeader(position: StepPosition, onSkip: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(Spacing.touchMin), verticalAlignment = Alignment.CenterVertically) {
        StepCount(position, Modifier.weight(1f))
        VerticalRule()
        TextActionButton(stringResource(R.string.wiz_skip), onSkip, Modifier.width(SkipButtonWidth).fillMaxHeight())
    }
}

@Composable
private fun StepCount(position: StepPosition, modifier: Modifier) {
    Text(
        stringResource(R.string.wiz_step_of, position.number.toString(), position.total.toString()).uppercase(),
        style = KLineType.fieldLabel,
        color = KLineTheme.colors.inkMuted,
        maxLines = 1,
        modifier = modifier.padding(horizontal = Spacing.fieldPaddingHorizontal),
    )
}

/** What a footer takes over from the header when there is no room for a header of its own. */
private class MergedHeader(val position: StepPosition, val onSkip: () -> Unit)

/**
 * Back (not on the first step) and the one action that moves on, as two 72 dp cells separated by a
 * rule. The first and last step have a single wide action named for what it does.
 */
@Composable
private fun WizardFooter(
    step: WizardStep,
    isFirst: Boolean,
    canContinue: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    mergedHeader: MergedHeader?,
) {
    Row(Modifier.fillMaxWidth().height(Spacing.touchMin), verticalAlignment = Alignment.CenterVertically) {
        if (mergedHeader != null) {
            StepCount(mergedHeader.position, Modifier.weight(1f))
            VerticalRule()
            TextActionButton(stringResource(R.string.wiz_skip), mergedHeader.onSkip, Modifier.weight(1f).fillMaxHeight())
            VerticalRule()
        }
        if (!isFirst) {
            TextActionButton(stringResource(R.string.wiz_back), onBack, Modifier.weight(1f).fillMaxHeight())
            VerticalRule()
        }
        PrimaryButton(stringResource(step.nextLabelRes()), onNext, Modifier.weight(1f).fillMaxHeight(), enabled = canContinue)
    }
}

private fun WizardStep.nextLabelRes(): Int = when (this) {
    WizardStep.WELCOME -> R.string.wiz_get_started
    WizardStep.DONE -> R.string.wiz_open_dashboard
    else -> R.string.wiz_next
}
