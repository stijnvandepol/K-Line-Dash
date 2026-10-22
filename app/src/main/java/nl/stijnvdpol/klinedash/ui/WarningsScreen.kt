package nl.stijnvdpol.klinedash.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.alert.AlertKind
import nl.stijnvdpol.klinedash.core.alert.AlertSettings
import nl.stijnvdpol.klinedash.service.formatNumber
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private val SoundCellWidth = 176.dp
internal val StepButtonWidth = 72.dp
internal val StepValueWidth = 104.dp
private val HeaderHeight = 56.dp

/** Two buttons and the value between them, with the rules that separate the three cells. */
private val StepperWidth = StepButtonWidth * 2 + StepValueWidth + Spacing.ruleThickness * 2

/** Holding a step button repeats it, so a limit 20 degrees away is not 20 taps. */
private const val REPEAT_DELAY_MS = 450L
private const val REPEAT_INTERVAL_MS = 90L
private const val SLOTS_PER_ROW = 2

/** The plus and minus are the touch targets of the page, so they are larger than a table value. */
private val StepGlyphSize = 40.sp

internal const val MINUS = "−"
internal const val PLUS = "+"

private class WarningRow(
    @StringRes val nameRes: Int,
    val kind: AlertKind,
    val limits: List<Pair<AlertLimit, Int>> = emptyList(),
)

/** One row per warning, in the order of [AlertKind]; each limit has the word that says which one it is. */
private val WarningRows = listOf(
    WarningRow(
        R.string.warning_coolant, AlertKind.COOLANT_HIGH,
        listOf(AlertLimit.COOLANT_CAUTION to R.string.limit_caution, AlertLimit.COOLANT_DANGER to R.string.limit_danger),
    ),
    WarningRow(
        R.string.warning_voltage_low, AlertKind.VOLTAGE_LOW,
        listOf(AlertLimit.VOLTAGE_RUNNING_LOW to R.string.limit_running, AlertLimit.VOLTAGE_ENGINE_OFF_LOW to R.string.limit_engine_off),
    ),
    WarningRow(R.string.warning_voltage_high, AlertKind.VOLTAGE_HIGH, listOf(AlertLimit.VOLTAGE_RUNNING_HIGH to R.string.limit_running)),
    WarningRow(R.string.warning_new_dtc, AlertKind.NEW_DTC),
    WarningRow(R.string.warning_mil, AlertKind.MIL_ON),
    WarningRow(R.string.warning_connection_lost, AlertKind.CONNECTION_LOST),
)

/**
 * SETUP > Warnings: a table with one 72 dp row per warning (switch with its name, Sound switch,
 * steppers for its limits) and "Reset to defaults" under it. No keyboard: steps only.
 */
@Composable
fun WarningsScreen() {
    val vm = graphViewModel { WarningsViewModel(it.alertSettingsRepository) }
    val loaded by vm.settings.collectAsStateWithLifecycle()
    val settings = loaded ?: return
    Column(Modifier.fillMaxSize()) {
        TableHeader()
        HorizontalRule()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            WarningRows.forEach { row -> WarningRowView(row, settings, vm) }
        }
        TextActionButton(
            stringResource(R.string.warnings_reset), vm::resetToDefaults,
            Modifier.fillMaxWidth().height(Spacing.touchMin),
        )
    }
}

@Composable
private fun TableHeader() {
    Row(Modifier.fillMaxWidth().height(HeaderHeight)) {
        HeaderCell(R.string.warnings_column_warning, Modifier.weight(1f))
        VerticalRule()
        HeaderCell(R.string.warnings_column_sound, Modifier.width(SoundCellWidth))
        VerticalRule()
        HeaderCell(R.string.warnings_column_limits, Modifier.width(StepperWidth * SLOTS_PER_ROW + Spacing.ruleThickness))
    }
}

@Composable
private fun HeaderCell(@StringRes labelRes: Int, modifier: Modifier) {
    Box(modifier.fillMaxHeight().padding(horizontal = Spacing.fieldPaddingHorizontal), contentAlignment = Alignment.CenterStart) {
        Text(stringResource(labelRes).uppercase(), style = KLineType.fieldLabel, color = KLineTheme.colors.inkMuted, maxLines = 1)
    }
}

@Composable
private fun WarningRowView(row: WarningRow, settings: AlertSettings, vm: WarningsViewModel) {
    val isOn = row.kind in settings.enabled
    Row(Modifier.fillMaxWidth().height(Spacing.touchMin)) {
        SwitchRow(stringResource(row.nameRes), isOn, { vm.setEnabled(row.kind, it) }, Modifier.weight(1f).fillMaxHeight())
        VerticalRule()
        // Sound only matters while the warning is on, so the switch is dimmed and still while it is off.
        SwitchRow(
            stringResource(R.string.warnings_sound), row.kind in settings.withSound, { vm.setSound(row.kind, it) },
            Modifier.width(SoundCellWidth).fillMaxHeight(), enabled = isOn,
        )
        repeat(SLOTS_PER_ROW) { slot ->
            VerticalRule()
            Box(Modifier.width(StepperWidth).fillMaxHeight()) {
                row.limits.getOrNull(slot)?.let { (limit, labelRes) ->
                    Stepper(limit, stringResource(labelRes), settings, isOn) { direction -> vm.step(limit, direction) }
                }
            }
        }
    }
    HorizontalRule()
}

/** [-] value [+]: the word for the limit in small capitals over the value and its unit. */
@Composable
private fun Stepper(limit: AlertLimit, label: String, settings: AlertSettings, isOn: Boolean, onStep: (direction: Int) -> Unit) {
    val colors = KLineTheme.colors
    Row(Modifier.fillMaxSize()) {
        StepButton(MINUS, "$label, lower", isOn && settings.canStep(limit, -1)) { onStep(-1) }
        VerticalRule()
        Column(
            Modifier.width(StepValueWidth).fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label.uppercase(), style = KLineType.fieldLabel, color = if (isOn) colors.inkMuted else colors.inkFaint, maxLines = 1, softWrap = false)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.grid / 2), verticalAlignment = Alignment.Bottom) {
                Text(
                    formatNumber(settings.valueOf(limit), limit.decimals),
                    style = KLineType.rowValue,
                    color = if (isOn) colors.ink else colors.inkFaint,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
                Text(limit.unit, style = KLineType.caption, color = colors.inkMuted, maxLines = 1)
            }
        }
        VerticalRule()
        StepButton(PLUS, "$label, raise", isOn && settings.canStep(limit, 1)) { onStep(1) }
    }
}

/**
 * A 72 x 72 dp cell that steps once on press and keeps stepping while it is held. Lit while pressed,
 * faint at the end of the range. A custom gesture, so the click action for accessibility is set by hand.
 */
@Composable
internal fun StepButton(glyph: String, description: String, enabled: Boolean, onStep: () -> Unit) {
    val colors = KLineTheme.colors
    val currentStep by rememberUpdatedState(onStep)
    var isPressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .width(StepButtonWidth)
            .fillMaxHeight()
            .background(if (isPressed) colors.surface else colors.background)
            .semantics {
                role = Role.Button
                contentDescription = description
                if (!enabled) disabled()
                onClick {
                    currentStep()
                    true
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    isPressed = true
                    currentStep()
                    coroutineScope {
                        val repeater = launch {
                            delay(REPEAT_DELAY_MS)
                            while (true) {
                                currentStep()
                                delay(REPEAT_INTERVAL_MS)
                            }
                        }
                        tryAwaitRelease()
                        repeater.cancel()
                    }
                    isPressed = false
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = KLineType.rowValue.copy(fontSize = StepGlyphSize), color = if (enabled) colors.ink else colors.inkFaint)
    }
}
