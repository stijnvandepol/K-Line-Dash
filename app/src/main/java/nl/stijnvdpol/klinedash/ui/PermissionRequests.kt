package nl.stijnvdpol.klinedash.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import nl.stijnvdpol.klinedash.appGraph
import nl.stijnvdpol.klinedash.data.SettingsRepository
import nl.stijnvdpol.klinedash.service.ObdService
import nl.stijnvdpol.klinedash.service.ObdServiceState
import nl.stijnvdpol.klinedash.transport.requiredPermissions

/** What to do once the permission dialog has been answered (granted or not). */
private enum class AfterPermissions { Nothing, Connect }

private val LocationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/**
 * The permission dialog of the activity and what follows its answer. Nothing is asked up front: each
 * permission is requested at the moment the screen that needs it says why (the setup, the Connection
 * page, GPS & Tracks). Denied permissions are fine: the simulator and Wi-Fi keep working, Bluetooth
 * shows an error. [revision] counts the answers, so screens read their device lists and checks again.
 */
@Stable
internal class PermissionRequests(
    private val context: Context,
    private val settings: SettingsRepository,
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
        if (action == AfterPermissions.Connect) ObdService.connect(context)
    }

    /** Starts the service when the app opens and the user wants to connect at start to an adapter they chose. */
    suspend fun startAtLaunch() {
        val connection = settings.connectionSettings.first()
        if (connection.autoConnect && connection.hasAdapter) ObdService.start(context)
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

    /** The Bluetooth permissions (Nearby devices, or Location on Android 11 and older). */
    fun requestBluetooth() = ask(missingPermissions(context, includeNotifications = false), then = AfterPermissions.Nothing)

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ask(arrayOf(Manifest.permission.POST_NOTIFICATIONS), then = AfterPermissions.Nothing)
    }

    fun hasBluetooth(): Boolean = missingPermissions(context, includeNotifications = false).isEmpty()

    /** True below Android 13, where notifications need no permission. */
    fun hasNotifications(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun hasLocation(): Boolean = LocationPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    private fun ask(permissions: Array<String>, then: AfterPermissions) {
        if (permissions.isEmpty()) return
        after = then
        launcher.launch(permissions)
    }
}

@Composable
internal fun rememberPermissionRequests(): PermissionRequests {
    val context = LocalContext.current
    val requests = remember(context) { PermissionRequests(context, context.appGraph.settingsRepository) }
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
