package nl.stijnvdpol.klinedash.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.car.CarMake
import nl.stijnvdpol.klinedash.core.car.EngineNotes
import nl.stijnvdpol.klinedash.data.CarSettings
import nl.stijnvdpol.klinedash.data.MakeChoice
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private val FactsColumnWidth = 440.dp
private val FactsColumnMinWidth = 400.dp
private const val FACTS_COLUMN_SHARE = 0.45f

@StringRes
private fun CarMake.labelRes(): Int = when (this) {
    CarMake.VW_GROUP -> R.string.car_make_vw_group
    CarMake.BMW_MINI -> R.string.car_make_bmw_mini
    CarMake.MERCEDES_SMART -> R.string.car_make_mercedes_smart
    CarMake.OPEL_VAUXHALL -> R.string.car_make_opel_vauxhall
    CarMake.FORD -> R.string.car_make_ford
    CarMake.RENAULT_DACIA -> R.string.car_make_renault_dacia
    CarMake.PSA -> R.string.car_make_psa
    CarMake.FIAT_ALFA_LANCIA -> R.string.car_make_fiat_alfa_lancia
    CarMake.VOLVO -> R.string.car_make_volvo
    CarMake.TOYOTA_LEXUS -> R.string.car_make_toyota_lexus
    CarMake.HONDA_ACURA -> R.string.car_make_honda_acura
    CarMake.NISSAN_INFINITI -> R.string.car_make_nissan_infiniti
    CarMake.MAZDA -> R.string.car_make_mazda
    CarMake.MITSUBISHI -> R.string.car_make_mitsubishi
    CarMake.SUBARU -> R.string.car_make_subaru
    CarMake.SUZUKI -> R.string.car_make_suzuki
    CarMake.HYUNDAI_KIA -> R.string.car_make_hyundai_kia
}

/** The brands under a make whose name alone does not say them; null when the label is enough. */
@StringRes
private fun CarMake.brandsRes(): Int? = if (this == CarMake.VW_GROUP) R.string.car_make_vw_group_brands else null

@StringRes
private fun EngineNotes.labelRes(): Int = when (this) {
    EngineNotes.TOYOTA_2ZZ_GE -> R.string.car_engine_toyota_2zz_ge
}

@StringRes
private fun EngineNotes.hintRes(): Int = when (this) {
    EngineNotes.TOYOTA_2ZZ_GE -> R.string.car_engine_toyota_2zz_ge_hint
}

/**
 * SETUP > Car: what the app cannot read from the car, and the make that decides whose trouble code
 * meanings the DIAG page shows. The red line (with the engine notes, when the make has any) sits in
 * a column of its own beside the list of makes, which scrolls by itself because it is long; where
 * the section is too narrow for both (see [isCarStacked]) they follow each other in one scrolling column.
 */
@Composable
internal fun CarScreen() {
    val vm = graphViewModel { CarViewModel(it.carSettingsRepository) }
    val loaded by vm.settings.collectAsStateWithLifecycle()
    val settings = loaded ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (isCarStacked(maxWidth.value)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                CarFacts(settings, vm)
                MakeHeader()
                MakeRows(settings, vm)
            }
        } else {
            val factsWidth = sideColumnWidth(maxWidth, FACTS_COLUMN_SHARE, FactsColumnMinWidth, FactsColumnWidth)
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(factsWidth).fillMaxHeight().verticalScroll(rememberScrollState())) { CarFacts(settings, vm) }
                VerticalRule()
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    MakeHeader()
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { MakeRows(settings, vm) }
                }
            }
        }
    }
}

/** The red line, and a switch for each engine of the chosen make that has notes. */
@Composable
private fun CarFacts(settings: CarSettings, vm: CarViewModel) {
    RedlineStepper(settings, vm)
    HorizontalRule()
    RedlineHint()
    HorizontalRule()
    engineNotesChoices(settings).forEach { notes ->
        SwitchRow(
            label = stringResource(notes.labelRes()),
            checked = settings.engineNotes == notes,
            onChange = { vm.setEngineNotes(notes, it) },
        )
        HintText(stringResource(notes.hintRes()))
        HorizontalRule()
    }
}

@Composable
private fun MakeHeader() {
    HeaderCell(stringResource(R.string.car_make_title), Modifier.fillMaxWidth().height(Spacing.tableHeaderHeight))
    HorizontalRule()
}

/** Automatic, Other make, then the makes in the order of [CarMake]: German first, then the other European makes, then Japanese and Korean. */
@Composable
private fun MakeRows(settings: CarSettings, vm: CarViewModel) {
    MakeRow(
        isSelected = settings.makeChoice == MakeChoice.Automatic,
        onClick = { vm.chooseMake(MakeChoice.Automatic) },
        title = stringResource(R.string.car_make_automatic),
        detail = automaticSummary(settings),
    )
    MakeRow(
        isSelected = settings.makeChoice == MakeChoice.Other,
        onClick = { vm.chooseMake(MakeChoice.Other) },
        title = stringResource(R.string.car_make_other),
        detail = stringResource(R.string.car_make_other_hint),
    )
    CarMake.entries.forEach { make ->
        val choice = MakeChoice.Specific(make)
        MakeRow(
            isSelected = settings.makeChoice == choice,
            onClick = { vm.chooseMake(choice) },
            title = stringResource(make.labelRes()),
            detail = make.brandsRes()?.let { stringResource(it) },
        )
    }
}

/** What the Automatic row says: the make the VIN points to, or why there is none. */
@Composable
private fun automaticSummary(settings: CarSettings): String = when (val detection = automaticDetectionOf(settings)) {
    AutomaticDetection.NoVin -> stringResource(R.string.car_make_not_detected)
    is AutomaticDetection.OtherMake -> stringResource(R.string.car_make_detected_other, detection.maker)
    is AutomaticDetection.Detected -> stringResource(R.string.car_make_detected, stringResource(detection.make.labelRes()), detection.maker)
}

@Composable
private fun MakeRow(isSelected: Boolean, onClick: () -> Unit, title: String, detail: String?) {
    // The page opens with the chosen make in view: Toyota is twelfth in a list a phone shows five of.
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) { if (isSelected) requester.bringIntoView() }
    SelectableRow(selected = isSelected, onClick = onClick, modifier = Modifier.bringIntoViewRequester(requester)) {
        Column {
            Text(title, style = KLineType.body, color = if (isSelected) KLineTheme.colors.ink else KLineTheme.colors.inkMuted)
            detail?.let { Text(it, style = KLineType.caption, color = KLineTheme.colors.inkMuted) }
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
internal fun RedlineHint(modifier: Modifier = Modifier) = HintText(stringResource(R.string.car_redline_hint), modifier)

/** A line of `caption` under a row, in the row's padding. */
@Composable
internal fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = KLineType.caption,
        color = KLineTheme.colors.inkMuted,
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
    )
}
