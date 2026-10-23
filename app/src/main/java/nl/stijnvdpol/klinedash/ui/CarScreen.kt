package nl.stijnvdpol.klinedash.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.data.CarSettings
import nl.stijnvdpol.klinedash.data.EngineProfile
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private class ProfileChoice(val profile: EngineProfile, @StringRes val labelRes: Int, @StringRes val hintRes: Int)

/** The trouble code profiles, the general one first. */
private val ProfileChoices = listOf(
    ProfileChoice(EngineProfile.GENERIC, R.string.car_codes_generic, R.string.car_codes_generic_hint),
    ProfileChoice(EngineProfile.TOYOTA_2ZZ_GE, R.string.car_codes_toyota, R.string.car_codes_toyota_hint),
)

/**
 * SETUP > Car: what the app cannot read from the car. The red line, which sets the scale of every
 * tachometer, and the trouble code profile, which decides whose meanings and notes the DIAG page
 * shows. One scrolling column, so it needs no layout of its own on a small screen.
 */
@Composable
internal fun CarScreen() {
    val vm = graphViewModel { CarViewModel(it.carSettingsRepository) }
    val loaded by vm.settings.collectAsStateWithLifecycle()
    val settings = loaded ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        RedlineStepper(settings, vm)
        HorizontalRule()
        RedlineHint()
        HorizontalRule()
        HeaderCell(stringResource(R.string.car_codes_title), Modifier.fillMaxWidth().height(Spacing.tableHeaderHeight))
        HorizontalRule()
        ProfileChoices.forEach { choice ->
            val isSelected = settings.engineProfile == choice.profile
            SelectableRow(selected = isSelected, onClick = { vm.chooseProfile(choice.profile) }) {
                Column {
                    Text(stringResource(choice.labelRes), style = KLineType.body, color = if (isSelected) KLineTheme.colors.ink else KLineTheme.colors.inkMuted)
                    Text(stringResource(choice.hintRes), style = KLineType.caption, color = KLineTheme.colors.inkMuted)
                }
            }
        }
    }
}

/** The red line as a 72 dp stepper row, shared by SETUP > Car and the setup wizard. */
@Composable
internal fun RedlineStepper(settings: CarSettings, vm: CarViewModel) {
    StepperRow(
        label = stringResource(R.string.car_redline),
        value = settings.redlineRpm.toString(),
        unit = null,
        words = StepWords.LOWER_RAISE,
        onStep = vm::stepRedline,
        canStep = { canStepRedline(settings.redlineRpm, it) },
    )
}

/** One line under the red line stepper: what the number means. */
@Composable
internal fun RedlineHint(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.car_redline_hint),
        style = KLineType.caption,
        color = KLineTheme.colors.inkMuted,
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
    )
}
