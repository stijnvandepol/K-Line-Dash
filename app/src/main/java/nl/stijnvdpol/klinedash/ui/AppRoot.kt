package nl.stijnvdpol.klinedash.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.appGraph
import nl.stijnvdpol.klinedash.service.ObdService
import nl.stijnvdpol.klinedash.service.ObdServiceState
import nl.stijnvdpol.klinedash.transport.requiredPermissions
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme

/** What to do once the permission dialog has been answered (granted or not). */
private enum class AfterPermissions { Nothing, StartupAutoConnect, Connect }

private val LocationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private const val PREFS = "ui"
private const val KEY_PERMISSIONS_ASKED = "permissions_asked"

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val settingsRepository = context.appGraph.settingsRepository
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.Dashboard) }
    // Kept here, not in the SETUP page, so the page opens on the section the user last used.
    var setupSection by remember { mutableStateOf(SetupSection.Connection) }
    val overlayModel = graphViewModel { AlertOverlayViewModel(ObdServiceState.alerts) }
    val overlay by overlayModel.overlay.collectAsStateWithLifecycle()
    val openConnection = {
        setupSection = SetupSection.Connection
        screen = Screen.Setup
    }
    val openGps = {
        setupSection = SetupSection.GpsTracks
        screen = Screen.Setup
    }
    var permissionRevision by remember { mutableIntStateOf(0) }
    var after by remember { mutableStateOf(AfterPermissions.Nothing) }

    // Denied permissions are fine: the simulator and Wi-Fi keep working, Bluetooth shows an error.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionRevision++
        // Whatever was asked, the service re-checks the location permission; it costs nothing when nothing changed.
        ObdServiceState.notifyLocationPermissionChanged()
        val action = after
        after = AfterPermissions.Nothing
        scope.launch {
            val connect = when (action) {
                AfterPermissions.Connect -> true
                AfterPermissions.StartupAutoConnect -> settingsRepository.connectionSettings.first().autoConnect
                AfterPermissions.Nothing -> false
            }
            if (connect) ObdService.connect(context)
        }
    }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val missing = missingPermissions(context, includeNotifications = true)
        if (missing.isNotEmpty() && !prefs.getBoolean(KEY_PERMISSIONS_ASKED, false)) {
            prefs.edit().putBoolean(KEY_PERMISSIONS_ASKED, true).apply()
            after = AfterPermissions.StartupAutoConnect
            permissionLauncher.launch(missing)
        } else if (settingsRepository.connectionSettings.first().autoConnect) {
            ObdService.start(context)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = KLineTheme.colors.background) {
        WarningFrame(overlay.frame) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (screen) {
                    Screen.Dashboard -> DashboardScreen()
                    Screen.Sensors -> SensorsScreen(onOpenConnection = openConnection)
                    Screen.Diagnostics -> DiagnosticsScreen(onOpenConnection = openConnection)
                    Screen.Trips -> TripsScreen()
                    Screen.Setup -> SetupScreen(
                        setupSection,
                        onSelect = { setupSection = it },
                        onAllowLocation = {
                            after = AfterPermissions.Nothing
                            permissionLauncher.launch(LocationPermissions)
                        },
                    ) {
                        ConnectionScreen(
                            onConnect = { needsBluetooth ->
                                val missing = if (needsBluetooth) missingPermissions(context, includeNotifications = false) else emptyArray<String>()
                                if (missing.isEmpty()) {
                                    ObdService.connect(context)
                                } else {
                                    after = AfterPermissions.Connect
                                    permissionLauncher.launch(missing)
                                }
                            },
                            onScanRequested = {
                                val missing = missingPermissions(context, includeNotifications = false)
                                if (missing.isEmpty()) {
                                    true
                                } else {
                                    after = AfterPermissions.Nothing
                                    permissionLauncher.launch(missing)
                                    false
                                }
                            },
                            permissionRevision = permissionRevision,
                        )
                    }
                }
            }
            overlay.banner?.let { banner -> WarningBanner(banner, onDismiss = { overlayModel.dismiss(banner.alert) }) }
            NavBar(selected = screen, onSelect = { screen = it }, onOpenGps = openGps)
        }
    }
}

private fun missingPermissions(context: Context, includeNotifications: Boolean): Array<String> {
    val wanted = buildList {
        addAll(requiredPermissions())
        if (includeNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    return wanted
        .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        .toTypedArray()
}
