package nl.stijnvdpol.klinedash.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.LocalScreenProfile
import nl.stijnvdpol.klinedash.ui.theme.Spacing

/** The sections of the SETUP page, in menu order. */
internal enum class SetupSection(@StringRes val labelRes: Int) {
    Connection(R.string.setup_connection),
    Warnings(R.string.setup_warnings),
    Dashboard(R.string.setup_dashboard),
    Display(R.string.setup_display),
    Startup(R.string.setup_startup),
    GpsTracks(R.string.setup_gps_tracks),
    Help(R.string.setup_help),
    About(R.string.setup_about),
    Debug(R.string.setup_debug),
}

private val MenuWidth = 240.dp
private val SingleMenuRowHeight = 64.dp
private const val SINGLE_MENU_COLUMNS = 2
private const val BACK_GLYPH = "\u2190"
private val BackGlyphSize = 32.sp

/**
 * SETUP: a 240 dp menu on the left and the chosen section on the right. [section] is kept by the
 * caller, so the page opens on the section the user last used. On a narrow screen (see
 * [isSetupSplit]) the menu and the section take turns: the menu fills the screen as two columns of
 * rows, a tap opens the section, and a back strip along its left edge (or the back key) returns to
 * the menu. [isSectionOpen] and [onClose] are that state; a wide screen ignores them.
 *
 * @param help the Help section; it needs to open other sections and to run the setup again, which the caller owns.
 * @param about the About section.
 * @param connection the Connection section; it needs the permission handling of the activity, so the
 *   caller supplies it.
 * @param onAllowLocation asks for the location permission (GPS & Tracks); the activity owns that dialog.
 */
@Composable
internal fun SetupScreen(
    section: SetupSection,
    isSectionOpen: Boolean,
    onSelect: (SetupSection) -> Unit,
    onClose: () -> Unit,
    onAllowLocation: () -> Unit,
    help: @Composable () -> Unit,
    about: @Composable () -> Unit,
    connection: @Composable () -> Unit,
) {
    val content: @Composable () -> Unit = { SetupSectionContent(section, onAllowLocation, help, about, connection) }
    when {
        isSetupSplit(LocalScreenProfile.current) -> Row(Modifier.fillMaxSize()) {
            SetupMenu(section, onSelect, Modifier.width(MenuWidth).fillMaxHeight())
            VerticalRule()
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        }
        isSectionOpen -> {
            BackHandler(onBack = onClose)
            Row(Modifier.fillMaxSize()) {
                BackStrip(onClose, Modifier.width(Spacing.touchMin).fillMaxHeight())
                VerticalRule()
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        }
        else -> SetupSectionList(onSelect)
    }
}

@Composable
private fun SetupSectionContent(
    section: SetupSection,
    onAllowLocation: () -> Unit,
    help: @Composable () -> Unit,
    about: @Composable () -> Unit,
    connection: @Composable () -> Unit,
) {
    when (section) {
        SetupSection.Connection -> connection()
        SetupSection.Warnings -> WarningsScreen()
        SetupSection.Dashboard -> DashboardSetupScreen()
        SetupSection.Display -> DisplayScreen()
        SetupSection.Startup -> StartupSettingsScreen()
        SetupSection.GpsTracks -> GpsTracksScreen(onAllowLocation)
        SetupSection.Debug -> DebugScreen()
        SetupSection.Help -> help()
        SetupSection.About -> about()
    }
}

@Composable
private fun SetupMenu(section: SetupSection, onSelect: (SetupSection) -> Unit, modifier: Modifier) {
    Column(modifier) {
        SectionTitle(stringResource(R.string.nav_setup), Modifier.height(Spacing.sectionHeaderHeight))
        HorizontalRule()
        // Nine rows are taller than a 720 dp screen minus the bottom bar, so the menu scrolls.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SetupSection.entries.forEach { entry ->
                val isSelected = entry == section
                SelectableRow(selected = isSelected, onClick = { onSelect(entry) }) {
                    Text(
                        stringResource(entry.labelRes),
                        style = KLineType.body,
                        color = if (isSelected) KLineTheme.colors.ink else KLineTheme.colors.inkMuted,
                    )
                }
            }
        }
    }
}

/** The narrow screen's menu: the sections as two columns of rows under the title, scrolling if they still do not fit. */
@Composable
private fun SetupSectionList(onSelect: (SetupSection) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SectionTitle(stringResource(R.string.nav_setup), Modifier.height(Spacing.tableHeaderHeight))
        HorizontalRule()
        Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            val perColumn = (SetupSection.entries.size + SINGLE_MENU_COLUMNS - 1) / SINGLE_MENU_COLUMNS
            SetupSection.entries.chunked(perColumn).forEachIndexed { index, column ->
                if (index > 0) VerticalRule()
                Column(Modifier.weight(1f)) {
                    column.forEach { entry ->
                        SelectableRow(selected = false, onClick = { onSelect(entry) }, minHeight = SingleMenuRowHeight) {
                            Text(stringResource(entry.labelRes), style = KLineType.body, color = KLineTheme.colors.ink)
                        }
                    }
                }
            }
        }
    }
}

/** The way back from a section on a narrow screen: the whole height of the screen is the target, the arrow stays small. */
@Composable
private fun BackStrip(onBack: () -> Unit, modifier: Modifier) {
    val description = stringResource(R.string.setup_back_description)
    Box(
        modifier
            .clickable(role = Role.Button, onClick = onBack)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(BACK_GLYPH, style = KLineType.rowValue.copy(fontSize = BackGlyphSize), color = KLineTheme.colors.ink)
    }
}
