package nl.stijnvdpol.klinedash.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.appGraph
import nl.stijnvdpol.klinedash.data.DayNightMode
import nl.stijnvdpol.klinedash.data.ThemeSettings
import nl.stijnvdpol.klinedash.ui.theme.KLineColors
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.LocalActiveThemeName
import nl.stijnvdpol.klinedash.ui.theme.Spacing
import nl.stijnvdpol.klinedash.ui.theme.ThemeCatalog

private val HeaderHeight = 56.dp
private val ThemeColumnWidth = 440.dp
private val SwatchStripCell = 20.dp
private val SwatchStripGap = 4.dp

/** The mode rows: label, one-line explanation. */
private val ModeRows = listOf(
    Triple(DayNightMode.MANUAL, R.string.display_mode_manual, R.string.display_mode_manual_hint),
    Triple(DayNightMode.SCHEDULE, R.string.display_mode_schedule, R.string.display_mode_schedule_hint),
    Triple(DayNightMode.FOLLOW_SYSTEM, R.string.display_mode_follow, R.string.display_mode_follow_hint),
)

/**
 * SETUP > Display. Left, the theme list (and the folder for your own themes); right, how day and
 * night switch and the accent colour. Everything applies at once on every screen.
 */
@Composable
internal fun DisplayScreen() {
    val graph = LocalContext.current.appGraph
    val vm = graphViewModel { DisplayViewModel(it.themeSettingsRepository, it.themeCatalog) }
    val loadedSettings by vm.settings.collectAsStateWithLifecycle()
    val loadedCatalog by vm.catalog.collectAsStateWithLifecycle()
    // The user may have copied files since the app started.
    LaunchedEffect(Unit) { vm.reload() }
    val settings = loadedSettings ?: return
    val catalog = loadedCatalog ?: return
    var slot by remember { mutableStateOf(ThemeSlot.DAY) }
    Row(Modifier.fillMaxSize()) {
        ThemeColumn(settings, catalog, slot, { slot = it }, graph.themesDir.path, vm, Modifier.width(ThemeColumnWidth).fillMaxHeight())
        VerticalRule()
        OptionsColumn(settings, vm, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun ColumnHeader(@StringRes textRes: Int) {
    Box(Modifier.fillMaxWidth().height(HeaderHeight).padding(horizontal = Spacing.fieldPaddingHorizontal), contentAlignment = Alignment.CenterStart) {
        Text(stringResource(textRes).uppercase(), style = KLineType.fieldLabel, color = KLineTheme.colors.inkMuted, maxLines = 1)
    }
    HorizontalRule()
}

@Composable
private fun ThemeColumn(
    settings: ThemeSettings,
    catalog: ThemeCatalog,
    slot: ThemeSlot,
    onSlot: (ThemeSlot) -> Unit,
    folderPath: String,
    vm: DisplayViewModel,
    modifier: Modifier,
) {
    val isManual = settings.mode == DayNightMode.MANUAL
    val selectedId = when {
        isManual -> settings.manualThemeId
        slot == ThemeSlot.DAY -> settings.dayThemeId
        else -> settings.nightThemeId
    }
    Column(modifier.verticalScroll(rememberScrollState())) {
        ColumnHeader(R.string.display_theme_title)
        if (!isManual) {
            SlotToggle(slot, onSlot)
            HorizontalRule()
        }
        catalog.themes.forEach { theme ->
            SelectableRow(selected = theme.id == selectedId, onClick = { vm.chooseTheme(slot, theme.id) }) {
                Text(theme.name, style = KLineType.body, color = KLineTheme.colors.ink, maxLines = 1, modifier = Modifier.weight(1f))
                SwatchStrip(theme.colors)
            }
        }
        UserThemes(catalog, folderPath, vm::reload)
    }
}

/** Day or night: which of the two the list below chooses. The chosen cell is filled. */
@Composable
private fun SlotToggle(slot: ThemeSlot, onSlot: (ThemeSlot) -> Unit) {
    Row(Modifier.fillMaxWidth().height(Spacing.touchMin)) {
        listOf(ThemeSlot.DAY to R.string.display_day_theme, ThemeSlot.NIGHT to R.string.display_night_theme).forEachIndexed { index, (entry, labelRes) ->
            if (index > 0) VerticalRule()
            val cell = Modifier.weight(1f).fillMaxHeight()
            if (entry == slot) {
                PrimaryButton(stringResource(labelRes), { onSlot(entry) }, cell)
            } else {
                TextActionButton(stringResource(labelRes), { onSlot(entry) }, cell)
            }
        }
    }
}

/** Background, ink, accent, caution and danger side by side: enough to tell the themes apart at a glance. */
@Composable
private fun SwatchStrip(colors: KLineColors) {
    Row(Modifier.border(Spacing.ruleThickness, KLineTheme.colors.inkFaint).padding(SwatchStripGap), horizontalArrangement = Arrangement.spacedBy(SwatchStripGap)) {
        listOf(colors.background, colors.ink, colors.accent, colors.caution, colors.danger).forEach { color ->
            Box(Modifier.size(SwatchStripCell).border(Spacing.ruleThickness, KLineTheme.colors.rule).padding(Spacing.ruleThickness).background(color))
        }
    }
}

@Composable
private fun UserThemes(catalog: ThemeCatalog, folderPath: String, onReload: () -> Unit) {
    val colors = KLineTheme.colors
    ColumnHeader(R.string.display_your_themes)
    val hasUserThemes = catalog.themes.any { !it.isBuiltIn }
    Column(Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical), verticalArrangement = Arrangement.spacedBy(Spacing.grid)) {
        Text(folderPath, style = KLineType.caption, color = colors.ink)
        Text(stringResource(R.string.display_folder_hint), style = KLineType.caption, color = colors.inkMuted)
        if (!hasUserThemes && catalog.invalid.isEmpty()) {
            Text(stringResource(R.string.display_no_user_themes), style = KLineType.caption, color = colors.inkMuted)
        }
    }
    HorizontalRule()
    TextActionButton(stringResource(R.string.display_reload), onReload, Modifier.fillMaxWidth().height(Spacing.touchMin))
    if (catalog.invalid.isNotEmpty()) {
        HorizontalRule()
        ColumnHeader(R.string.display_invalid_header)
        catalog.invalid.forEach { file ->
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical)) {
                Text(file.fileName, style = KLineType.body, color = colors.ink)
                Text(file.reason, style = KLineType.caption, color = colors.danger)
            }
            HorizontalRule()
        }
    }
}

@Composable
private fun OptionsColumn(settings: ThemeSettings, vm: DisplayViewModel, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        ColumnHeader(R.string.display_mode_title)
        ModeRows.forEach { (mode, labelRes, hintRes) ->
            val isSelected = settings.mode == mode
            SelectableRow(selected = isSelected, onClick = { vm.setMode(mode) }) {
                Column {
                    Text(stringResource(labelRes), style = KLineType.body, color = if (isSelected) KLineTheme.colors.ink else KLineTheme.colors.inkMuted)
                    Text(stringResource(hintRes), style = KLineType.caption, color = KLineTheme.colors.inkMuted)
                }
            }
        }
        if (settings.mode == DayNightMode.SCHEDULE) {
            ScheduleRow(R.string.display_day_starts, settings.dayStartMinute) { vm.stepSchedule(ScheduleTime.DAY_START, it) }
            ScheduleRow(R.string.display_night_starts, settings.nightStartMinute) { vm.stepSchedule(ScheduleTime.NIGHT_START, it) }
        }
        if (settings.mode != DayNightMode.MANUAL) NowShowing()
        ColumnHeader(R.string.display_accent_title)
        AccentPicker(settings.accentOverride, vm::setAccent)
    }
}

/** The theme in effect right now, so the schedule and the head-unit mode can be checked at a glance. */
@Composable
private fun NowShowing() {
    Row(
        Modifier.fillMaxWidth().height(HeaderHeight).padding(horizontal = Spacing.fieldPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.display_now_showing).uppercase(), style = KLineType.fieldLabel, color = KLineTheme.colors.inkMuted, modifier = Modifier.weight(1f))
        Text(LocalActiveThemeName.current, style = KLineType.body, color = KLineTheme.colors.ink, maxLines = 1)
    }
    HorizontalRule()
}
