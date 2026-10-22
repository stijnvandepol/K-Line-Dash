package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.appGraph
import nl.stijnvdpol.klinedash.service.ObdServiceState
import nl.stijnvdpol.klinedash.ui.help.AboutScreen
import nl.stijnvdpol.klinedash.ui.help.HelpScreen
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.wizard.SetupWizard

/**
 * The whole app: the first-run setup while it has not been finished or skipped (and when SETUP >
 * Help runs it again), otherwise the five pages with the warning overlay and the bottom bar. Nothing
 * is drawn until the general settings are read, so neither the setup nor the dashboard flashes by.
 */
@Composable
fun AppRoot() {
    val general by LocalContext.current.appGraph.generalSettingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
    val permissions = rememberPermissionRequests()
    var isRerunningSetup by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf(Screen.Dashboard) }
    val loaded = general
    LaunchedEffect(loaded?.setupCompleted) { if (loaded?.setupCompleted == true) permissions.startAtLaunch() }

    Surface(modifier = Modifier.fillMaxSize(), color = KLineTheme.colors.background) {
        if (loaded == null) return@Surface
        CompositionLocalProvider(LocalUnitSystem provides loaded.unitSystem) {
            if (!loaded.setupCompleted || isRerunningSetup) {
                SetupWizard(permissions, onFinished = {
                    isRerunningSetup = false
                    screen = Screen.Dashboard
                })
            } else {
                MainPages(screen, { screen = it }, permissions, onRunSetupAgain = { isRerunningSetup = true })
            }
        }
    }
}

@Composable
private fun MainPages(
    screen: Screen,
    onScreen: (Screen) -> Unit,
    permissions: PermissionRequests,
    onRunSetupAgain: () -> Unit,
) {
    // Kept here, not in the SETUP page, so the page opens on the section the user last used.
    var setupSection by remember { mutableStateOf(SetupSection.Connection) }
    val overlayModel = graphViewModel { AlertOverlayViewModel(ObdServiceState.alerts) }
    val overlay by overlayModel.overlay.collectAsStateWithLifecycle()
    val connection = graphViewModel { ConnectionViewModel(it) }
    val openSetup = { section: SetupSection ->
        setupSection = section
        onScreen(Screen.Setup)
    }

    WarningFrame(overlay.frame) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (screen) {
                Screen.Dashboard -> DashboardScreen(
                    onSetUpAdapter = onRunSetupAgain,
                    onTrySimulator = { connection.useSimulator { permissions.connect(needsBluetooth = false) } },
                )
                Screen.Sensors -> SensorsScreen(onOpenConnection = { openSetup(SetupSection.Connection) })
                Screen.Diagnostics -> DiagnosticsScreen(onOpenConnection = { openSetup(SetupSection.Connection) })
                Screen.Trips -> TripsScreen()
                Screen.Setup -> SetupScreen(setupSection, onSelect = { setupSection = it }, onAllowLocation = permissions::requestLocation, help = {
                    HelpScreen(onOpenSection = { setupSection = it }, onRunSetupAgain = onRunSetupAgain)
                }, about = { AboutScreen() }) {
                    ConnectionScreen(
                        onConnect = permissions::connect,
                        onScanRequested = permissions::isReadyToScan,
                        permissionRevision = permissions.revision,
                    )
                }
            }
        }
        overlay.banner?.let { banner -> WarningBanner(banner, onDismiss = { overlayModel.dismiss(banner.alert) }) }
        NavBar(selected = screen, onSelect = onScreen, onOpenGps = { openSetup(SetupSection.GpsTracks) })
    }
}
