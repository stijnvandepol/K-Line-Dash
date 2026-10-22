package nl.stijnvdpol.klinedash.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

/** The sections of the SETUP page, in menu order. */
internal enum class SetupSection(@StringRes val labelRes: Int) {
    Connection(R.string.setup_connection),
    Warnings(R.string.setup_warnings),
    Dashboard(R.string.setup_dashboard),
    Display(R.string.setup_display),
    Startup(R.string.setup_startup),
    GpsTracks(R.string.setup_gps_tracks),
    Debug(R.string.setup_debug),
}

private val MenuWidth = 240.dp

/**
 * SETUP: a 240 dp menu on the left and the chosen section on the right. [section] is kept by the
 * caller, so the page opens on the section the user last used.
 *
 * @param connection the Connection section; it needs the permission handling of the activity, so the
 *   caller supplies it.
 * @param onAllowLocation asks for the location permission (GPS & Tracks); the activity owns that dialog.
 */
@Composable
internal fun SetupScreen(
    section: SetupSection,
    onSelect: (SetupSection) -> Unit,
    onAllowLocation: () -> Unit,
    connection: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        SetupMenu(section, onSelect, Modifier.width(MenuWidth).fillMaxHeight())
        VerticalRule()
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (section) {
                SetupSection.Connection -> connection()
                SetupSection.Warnings -> WarningsScreen()
                SetupSection.Dashboard -> DashboardSetupScreen()
                SetupSection.Display -> DisplayScreen()
                SetupSection.Startup -> StartupSettingsScreen()
                SetupSection.GpsTracks -> GpsTracksScreen(onAllowLocation)
                SetupSection.Debug -> DebugScreen()
            }
        }
    }
}

@Composable
private fun SetupMenu(section: SetupSection, onSelect: (SetupSection) -> Unit, modifier: Modifier) {
    Column(modifier) {
        SectionTitle(stringResource(R.string.nav_setup), Modifier.height(Spacing.sectionHeaderHeight))
        HorizontalRule()
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
