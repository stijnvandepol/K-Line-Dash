package nl.stijnvdpol.klinedash.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.data.ClusterStyle
import nl.stijnvdpol.klinedash.data.DayNightMode
import nl.stijnvdpol.klinedash.data.ThemeSettings
import nl.stijnvdpol.klinedash.ui.cluster.ClusterPreview
import nl.stijnvdpol.klinedash.ui.theme.KLineColors
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.LocalActiveThemeName
import nl.stijnvdpol.klinedash.ui.theme.Spacing
import nl.stijnvdpol.klinedash.ui.theme.ThemeCatalog
import nl.stijnvdpol.klinedash.ui.theme.formatMinuteOfDay

private val HeaderHeight = 56.dp
private val ThemeColumnWidth = 440.dp
private val SwatchStripCell = 20.dp
private val SwatchStripGap = 4.dp
private val PreviewGap = 12.dp

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
    val vm = graphViewModel { DisplayViewModel(it.themeSettingsRepository, it.themeCatalog) }
    val loadedSettings by vm.settings.collectAsStateWithLifecycle()
    val loadedCatalog by vm.catalog.collectAsStateWithLifecycle()
    // The user may have copied files since the app started.
    LaunchedEffect(Unit) { vm.reload() }
    val settings = loadedSettings ?: return
    val catalog = loadedCatalog ?: return
    var slot by remember { mutableStateOf(ThemeSlot.DAY) }
    Row(Modifier.fillMaxSize()) {
        ThemeColumn(settings, catalog, slot, { slot = it }, vm, Modifier.width(ThemeColumnWidth).fillMaxHeight())
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
    vm: DisplayViewModel,
    modifier: Modifier,
) {
    val isManual = settings.mode == DayNightMode.MANUAL
    val selectedId = when {
        isManual -> settings.manualThemeId
        slot == ThemeSlot.DAY -> settings.dayThemeId
        else -> settings.nightThemeId
    }
    // The list scrolls on its own and Reload stays at the bottom, so on a short screen (or under a
    // warning banner) nothing ends up out of reach below the fold.
    Column(modifier) {
        ColumnHeader(R.string.display_theme_title)
        if (!isManual) {
            SlotToggle(slot, onSlot)
            HorizontalRule()
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            catalog.themes.forEach { theme ->
                SelectableRow(selected = theme.id == selectedId, onClick = { vm.chooseTheme(slot, theme.id) }) {
                    Text(theme.name, style = KLineType.body, color = KLineTheme.colors.ink, maxLines = 1, modifier = Modifier.weight(1f))
                    ClusterPreview(theme.colors, theme.look.style)
                    Spacer(Modifier.width(PreviewGap))
                    SwatchStrip(theme.colors)
                }
            }
            UserThemes(catalog)
        }
        HorizontalRule()
        TextActionButton(stringResource(R.string.display_reload), vm::reload, Modifier.fillMaxWidth().height(Spacing.touchMin))
    }
}

/** Day or night: which of the two the list below chooses. */
@Composable
private fun SlotToggle(slot: ThemeSlot, onSlot: (ThemeSlot) -> Unit) {
    val slots = ThemeSlot.entries
    ChoiceCells(
        labels = slots.map { stringResource(if (it == ThemeSlot.DAY) R.string.display_day_theme else R.string.display_night_theme) },
        selectedIndex = slots.indexOf(slot),
        onSelect = { onSlot(slots[it]) },
    )
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
private fun UserThemes(catalog: ThemeCatalog) {
    val colors = KLineTheme.colors
    ColumnHeader(R.string.display_your_themes)
    val hasUserThemes = catalog.themes.any { !it.isBuiltIn }
    Column(Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical), verticalArrangement = Arrangement.spacedBy(Spacing.grid)) {
        Text(catalog.userFolderPath, style = KLineType.caption, color = colors.ink)
        Text(stringResource(R.string.display_folder_hint), style = KLineType.caption, color = colors.inkMuted)
        if (!hasUserThemes && catalog.invalid.isEmpty()) {
            Text(stringResource(R.string.display_no_user_themes), style = KLineType.caption, color = colors.inkMuted)
        }
    }
    if (catalog.invalid.isNotEmpty()) {
        HorizontalRule()
        ColumnHeader(R.string.display_invalid_header)
        catalog.invalid.forEachIndexed { index, file ->
            if (index > 0) HorizontalRule()
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical)) {
                Text(file.fileName, style = KLineType.body, color = colors.ink)
                Text(file.reason, style = KLineType.caption, color = colors.danger)
            }
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
            StepperRow(
                stringResource(R.string.display_day_starts), formatMinuteOfDay(settings.dayStartMinute), unit = null,
                StepWords.EARLIER_LATER, { vm.stepSchedule(ScheduleTime.DAY_START, it) },
            )
            HorizontalRule()
            StepperRow(
                stringResource(R.string.display_night_starts), formatMinuteOfDay(settings.nightStartMinute), unit = null,
                StepWords.EARLIER_LATER, { vm.stepSchedule(ScheduleTime.NIGHT_START, it) },
            )
            HorizontalRule()
        }
        if (settings.mode != DayNightMode.MANUAL) NowShowing()
        ColumnHeader(R.string.display_cluster_title)
        ClusterChoice(settings.clusterOverride, vm::setCluster)
        ColumnHeader(R.string.display_accent_title)
        AccentPicker(settings.accentOverride, vm::setAccent)
    }
}

/** The cluster row: "Theme default" first, then the five styles, one global override as 72 dp cells. */
@Composable
private fun ClusterChoice(chosen: ClusterStyle?, onChoose: (ClusterStyle?) -> Unit) {
    val styles = ClusterStyle.entries
    val labels = listOf(stringResource(R.string.display_cluster_default)) + styles.map { stringResource(it.labelRes()) }
    ChoiceCells(labels, selectedIndex = if (chosen == null) 0 else styles.indexOf(chosen) + 1, onSelect = { onChoose(if (it == 0) null else styles[it - 1]) })
    HorizontalRule()
    Text(
        stringResource(R.string.display_cluster_hint),
        style = KLineType.caption, color = KLineTheme.colors.inkMuted,
        modifier = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
    )
    HorizontalRule()
}

@StringRes
private fun ClusterStyle.labelRes(): Int = when (this) {
    ClusterStyle.BAR -> R.string.display_cluster_bar
    ClusterStyle.TWIN -> R.string.display_cluster_twin
    ClusterStyle.CENTER -> R.string.display_cluster_center
    ClusterStyle.ARC -> R.string.display_cluster_arc
    ClusterStyle.VIRTUAL -> R.string.display_cluster_virtual
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
