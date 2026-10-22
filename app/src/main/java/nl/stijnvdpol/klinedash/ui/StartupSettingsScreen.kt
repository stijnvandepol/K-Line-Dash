package nl.stijnvdpol.klinedash.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.data.ActionProblem
import nl.stijnvdpol.klinedash.data.MAX_ACTION_LENGTH
import nl.stijnvdpol.klinedash.data.StartupSettings
import nl.stijnvdpol.klinedash.data.WakeDirection
import nl.stijnvdpol.klinedash.data.actionProblem
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private val LastReceivedWidth = 176.dp
private val RemoveWidth = 136.dp
private val AddButtonWidth = 200.dp

/**
 * SETUP > Startup: three switches, the wake and sleep broadcast lists with the time each last
 * arrived, a field to add an action, and "Keep the app alive" below. One scrolling column.
 */
@Composable
fun StartupSettingsScreen() {
    val vm = graphViewModel { StartupSettingsViewModel(it.startupSettingsRepository) }
    val loaded by vm.settings.collectAsStateWithLifecycle()
    val settings = loaded ?: return
    val context = LocalContext.current
    // The system screens we link to change what is allowed; read it again whenever we come back.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val access = remember(resumes) { readSystemAccess(context) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SwitchRow(stringResource(R.string.startup_boot), settings.startOnBoot, vm::setStartOnBoot)
        HorizontalRule()
        SwitchRow(stringResource(R.string.startup_wake), settings.reconnectOnWake, vm::setReconnectOnWake)
        HorizontalRule()
        SwitchRow(stringResource(R.string.startup_open_dashboard), settings.openDashboardOnStart, vm::setOpenDashboardOnStart)
        HorizontalRule()
        if (!access.canDrawOverlays) {
            OverlayNote()
            HorizontalRule()
        }
        SectionTitle(stringResource(R.string.startup_signals_title), Modifier.height(Spacing.tableHeaderHeight))
        Text(
            stringResource(R.string.startup_signals_hint),
            style = KLineType.caption,
            color = KLineTheme.colors.inkMuted,
            modifier = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal).padding(bottom = Spacing.grid),
        )
        ActionGroup(R.string.startup_wake_group, settings.wakeActions, settings, vm)
        ActionGroup(R.string.startup_sleep_group, settings.sleepActions, settings, vm)
        AddActionRow(settings, vm)
        TextActionButton(
            stringResource(R.string.startup_reset), vm::resetActions,
            Modifier.fillMaxWidth().height(Spacing.touchMin),
        )
        HorizontalRule()
        KeepAliveSection(access)
    }
}

@Composable
private fun OverlayNote() {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(Spacing.touchMin), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(if (failed) R.string.startup_settings_unavailable else R.string.startup_overlay_needed),
            style = KLineType.caption,
            color = KLineTheme.colors.inkMuted,
            maxLines = 3,
            modifier = Modifier.weight(1f).padding(horizontal = Spacing.fieldPaddingHorizontal),
        )
        VerticalRule()
        TextActionButton(
            stringResource(R.string.startup_allow),
            { failed = !openSystemScreen(context, overlaySettingsIntent(context)) },
            Modifier.width(RemoveWidth).fillMaxHeight(),
        )
    }
}

/** Header (group name, "Last received"), then one 72 dp row per action: mono text, time, Remove. */
@Composable
private fun ActionGroup(@StringRes titleRes: Int, actions: List<String>, settings: StartupSettings, vm: StartupSettingsViewModel) {
    val colors = KLineTheme.colors
    HorizontalRule()
    Row(Modifier.fillMaxWidth().height(Spacing.tableHeaderHeight)) {
        HeaderCell(stringResource(titleRes), Modifier.weight(1f).fillMaxHeight())
        HeaderCell(stringResource(R.string.startup_last_received), Modifier.width(LastReceivedWidth + RemoveWidth).fillMaxHeight())
    }
    HorizontalRule()
    actions.forEach { action ->
        Row(Modifier.fillMaxWidth().height(Spacing.touchMin), verticalAlignment = Alignment.CenterVertically) {
            Text(
                action, style = KLineType.mono, color = colors.ink, maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = Spacing.fieldPaddingHorizontal),
            )
            val time = settings.lastReceived[action]
            Text(
                time?.let(::formatTripStart) ?: stringResource(R.string.startup_never),
                style = KLineType.caption,
                color = if (time == null) colors.inkMuted else colors.ink,
                maxLines = 1,
                modifier = Modifier.width(LastReceivedWidth).padding(horizontal = Spacing.fieldPaddingHorizontal),
            )
            VerticalRule()
            TextActionButton(
                stringResource(R.string.startup_remove), { vm.removeAction(action) },
                Modifier.width(RemoveWidth).fillMaxHeight(),
            )
        }
        HorizontalRule()
    }
}

/** One text field and two buttons, since the direction (wake or sleep) is not in the action's name. */
@Composable
private fun AddActionRow(settings: StartupSettings, vm: StartupSettingsViewModel) {
    var draft by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<ActionProblem?>(null) }
    val add = { direction: WakeDirection ->
        problem = actionProblem(draft, settings.allActions)
        if (problem == null) {
            vm.addAction(direction, draft)
            draft = ""
        }
    }
    // Above the field, not below it: with the keyboard open the space under the field is off screen.
    problem?.let {
        Text(
            problemText(it), style = KLineType.caption, color = KLineTheme.colors.danger,
            modifier = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal).padding(top = Spacing.grid),
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid).height(Spacing.touchMin),
        horizontalArrangement = Arrangement.spacedBy(Spacing.grid),
    ) {
        FormTextField(
            draft, { draft = it; problem = null },
            Modifier.weight(1f).fillMaxHeight(),
            placeholder = stringResource(R.string.startup_action_hint),
            textStyle = KLineType.mono,
        )
        SecondaryButton(stringResource(R.string.startup_add_wake), { add(WakeDirection.WAKE) }, Modifier.width(AddButtonWidth).fillMaxHeight())
        SecondaryButton(stringResource(R.string.startup_add_sleep), { add(WakeDirection.SLEEP) }, Modifier.width(AddButtonWidth).fillMaxHeight())
    }
}

@Composable
private fun problemText(problem: ActionProblem): String = when (problem) {
    ActionProblem.EMPTY -> stringResource(R.string.startup_problem_empty)
    ActionProblem.TOO_LONG -> stringResource(R.string.startup_problem_long, MAX_ACTION_LENGTH.toString())
    ActionProblem.BAD_CHARACTERS -> stringResource(R.string.startup_problem_chars)
    ActionProblem.DUPLICATE -> stringResource(R.string.startup_problem_duplicate)
}
