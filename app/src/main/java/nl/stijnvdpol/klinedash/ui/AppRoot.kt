package nl.stijnvdpol.klinedash.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.appGraph
import nl.stijnvdpol.klinedash.data.SettingsRepository
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
    var screen by remember { mutableStateOf(Screen.Dashboard) }
    // Kept here, not in the SETUP page, so the page opens on the section the user last used.
    var setupSection by remember { mutableStateOf(SetupSection.Connection) }
    val overlayModel = graphViewModel { AlertOverlayViewModel(ObdServiceState.alerts) }
    val overlay by overlayModel.overlay.collectAsStateWithLifecycle()
    val permissions = rememberPermissionRequests()
    LaunchedEffect(Unit) { permissions.askOnFirstStart() }
    val openSetup = { section: SetupSection ->
        setupSection = section
        screen = Screen.Setup
    }

    Surface(modifier = Modifier.fillMaxSize(), color = KLineTheme.colors.background) {
        WarningFrame(overlay.frame) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (screen) {
                    Screen.Dashboard -> DashboardScreen()
                    Screen.Sensors -> SensorsScreen(onOpenConnection = { openSetup(SetupSection.Connection) })
                    Screen.Diagnostics -> DiagnosticsScreen(onOpenConnection = { openSetup(SetupSection.Connection) })
                    Screen.Trips -> TripsScreen()
                    Screen.Setup -> SetupScreen(setupSection, onSelect = { setupSection = it }, onAllowLocation = permissions::requestLocation) {
                        ConnectionScreen(
                            onConnect = permissions::connect,
                            onScanRequested = permissions::isReadyToScan,
                            permissionRevision = permissions.revision,
                        )
                    }
                }
            }
            overlay.banner?.let { banner -> WarningBanner(banner, onDismiss = { overlayModel.dismiss(banner.alert) }) }
            NavBar(selected = screen, onSelect = { screen = it }, onOpenGps = { openSetup(SetupSection.GpsTracks) })
        }
    }
}

/**
 * The permission dialog of the activity and what follows its answer. Denied permissions are fine:
 * the simulator and Wi-Fi keep working, Bluetooth shows an error. [revision] counts the answers, so
 * the Connection page reads its device lists again.
 */
@Stable
private class PermissionRequests(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    var revision by mutableIntStateOf(0)
        private set

    private var after = AfterPermissions.Nothing
    lateinit var launcher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>

    fun onAnswered() {
        revision++
        // Whatever was asked, the service re-checks the location permission; it costs nothing when nothing changed.
        ObdServiceState.notifyLocationPermissionChanged()
        val action = after
        after = AfterPermissions.Nothing
        scope.launch {
            val connect = when (action) {
                AfterPermissions.Connect -> true
                AfterPermissions.StartupAutoConnect -> settings.connectionSettings.first().autoConnect
                AfterPermissions.Nothing -> false
            }
            if (connect) ObdService.connect(context)
        }
    }

    /** On the very first start the app asks for everything it needs; afterwards it only connects at start when set to. */
    suspend fun askOnFirstStart() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val missing = missingPermissions(context, includeNotifications = true)
        if (missing.isNotEmpty() && !prefs.getBoolean(KEY_PERMISSIONS_ASKED, false)) {
            prefs.edit().putBoolean(KEY_PERMISSIONS_ASKED, true).apply()
            ask(missing, then = AfterPermissions.StartupAutoConnect)
        } else if (settings.connectionSettings.first().autoConnect) {
            ObdService.start(context)
        }
    }

    /** Connects now, or after the dialog when a Bluetooth transport still misses its permissions. */
    fun connect(needsBluetooth: Boolean) {
        val missing = if (needsBluetooth) missingPermissions(context, includeNotifications = false) else emptyArray()
        if (missing.isEmpty()) ObdService.connect(context) else ask(missing, then = AfterPermissions.Connect)
    }

    /** True when a BLE scan may start; otherwise asks, and the user taps Scan again. */
    fun isReadyToScan(): Boolean {
        val missing = missingPermissions(context, includeNotifications = false)
        if (missing.isNotEmpty()) ask(missing, then = AfterPermissions.Nothing)
        return missing.isEmpty()
    }

    fun requestLocation() = ask(LocationPermissions, then = AfterPermissions.Nothing)

    private fun ask(permissions: Array<String>, then: AfterPermissions) {
        after = then
        launcher.launch(permissions)
    }
}

@Composable
private fun rememberPermissionRequests(): PermissionRequests {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requests = remember(context, scope) { PermissionRequests(context, context.appGraph.settingsRepository, scope) }
    requests.launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { requests.onAnswered() }
    return requests
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
