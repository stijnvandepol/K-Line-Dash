package nl.stijnvdpol.klinedash.ui.wizard

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.ui.TwoPane
import nl.stijnvdpol.klinedash.ui.ButtonRow
import nl.stijnvdpol.klinedash.ui.DisplayViewModel
import nl.stijnvdpol.klinedash.ui.HorizontalRule
import nl.stijnvdpol.klinedash.ui.LocalUnitSystem
import nl.stijnvdpol.klinedash.ui.PermissionRequests
import nl.stijnvdpol.klinedash.ui.SecondaryButton
import nl.stijnvdpol.klinedash.ui.SectionTitle
import nl.stijnvdpol.klinedash.ui.SelectableRow
import nl.stijnvdpol.klinedash.ui.SwatchStrip
import nl.stijnvdpol.klinedash.ui.SwitchRow
import nl.stijnvdpol.klinedash.ui.UnitSystemChoice
import nl.stijnvdpol.klinedash.ui.cluster.ClusterPreview
import nl.stijnvdpol.klinedash.ui.graphViewModel
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing
import nl.stijnvdpol.klinedash.ui.theme.ThemeDefinition
import nl.stijnvdpol.klinedash.data.DayNightMode

private val ThemeGap = 12.dp

/** A theme row needs about this much width for its preview, its swatches and a name; two fit side by side from twice this. */
private val ThemeCellMinWidth = 340.dp

/**
 * Step 5: units, theme and whether trips record by themselves. Every choice applies at once, so the
 * screen already looks the way it will. Permissions the app can use are offered here with their
 * reason, and only while they are missing.
 */
@Composable
internal fun PreferencesStep(wizard: WizardViewModel, permissions: PermissionRequests) {
    val display = graphViewModel { DisplayViewModel.forGraph(it) }
    val isRecordingTrips by wizard.isRecordingTrips.collectAsStateWithLifecycle()
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val hasLocation = remember(permissions.revision, resumes) { permissions.hasLocation() }
    val hasNotifications = remember(permissions.revision, resumes) { permissions.hasNotifications() }

    TwoPane(
        left = {
            SectionTitle(stringResource(R.string.units_title))
            UnitSystemChoice(LocalUnitSystem.current, display::setUnits)
            SwitchRow(stringResource(R.string.wiz_prefs_trips), isRecordingTrips ?: true, wizard::setRecordTrips)
            HorizontalRule()
            StepText(stringResource(R.string.wiz_prefs_trips_hint))
            if (!hasLocation) AllowRow(R.string.wiz_prefs_location_hint, permissions::requestLocation)
            if (!hasNotifications) AllowRow(R.string.wiz_prefs_notifications_hint, permissions::requestNotifications)
        },
        right = {
            SectionTitle(stringResource(R.string.display_theme_title))
            ThemeGrid(display)
        },
    )
}

/** What a permission is for, in one line, and the button that asks for it. */
@Composable
private fun AllowRow(hintRes: Int, onAllow: () -> Unit) {
    HorizontalRule(Modifier.padding(top = Spacing.grid))
    StepText(stringResource(hintRes), Modifier.padding(top = Spacing.grid), color = KLineTheme.colors.ink)
    ButtonRow { SecondaryButton(stringResource(R.string.wiz_permission_allow), onAllow, Modifier.weight(1f)) }
}

/** The built-in and user themes as rows of a cluster sketch, the five swatches and the name; two to a line when there is room. */
@Composable
private fun ThemeGrid(display: DisplayViewModel) {
    val settings by display.settings.collectAsStateWithLifecycle()
    val catalog by display.catalog.collectAsStateWithLifecycle()
    val loaded = settings ?: return
    val themes = catalog?.themes ?: return
    val selectedId = loaded.manualThemeId.takeIf { loaded.mode == DayNightMode.MANUAL }
    HorizontalRule()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val perRow = if (maxWidth >= ThemeCellMinWidth * 2) 2 else 1
        Column {
            themes.chunked(perRow).forEach { rowThemes ->
                Row {
                    rowThemes.forEach { theme ->
                        ThemeCell(theme, theme.id == selectedId, { display.chooseOnlyTheme(theme.id) }, Modifier.weight(1f))
                    }
                    // A last row with one theme keeps its width, so the cells line up.
                    repeat(perRow - rowThemes.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ThemeCell(theme: ThemeDefinition, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = KLineTheme.colors
    SelectableRow(selected = isSelected, onClick = onClick, modifier = modifier) {
        ClusterPreview(theme.colors, theme.look.style)
        Spacer(Modifier.width(ThemeGap))
        Column(Modifier.weight(1f)) {
            Text(theme.name, style = KLineType.body, color = if (isSelected) colors.ink else colors.inkMuted, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) { SwatchStrip(theme.colors) }
        }
    }
}
