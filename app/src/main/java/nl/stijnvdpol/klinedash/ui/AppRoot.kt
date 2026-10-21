package nl.stijnvdpol.klinedash.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.appGraph
import nl.stijnvdpol.klinedash.service.ObdService
import nl.stijnvdpol.klinedash.transport.requiredPermissions
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private enum class Screen(@StringRes val labelRes: Int) {
    Dashboard(R.string.nav_dashboard),
    Sensors(R.string.nav_sensors),
    Connection(R.string.nav_connection),
    Debug(R.string.nav_debug),
}

/** What to do once the permission dialog has been answered (granted or not). */
private enum class AfterPermissions { Nothing, StartupAutoConnect, Connect }

private const val PREFS = "ui"
private const val KEY_PERMISSIONS_ASKED = "permissions_asked"

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val settingsRepository = context.appGraph.settingsRepository
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.Dashboard) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    var after by remember { mutableStateOf(AfterPermissions.Nothing) }

    // Denied permissions are fine: the simulator and Wi-Fi keep working, Bluetooth shows an error.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionRevision++
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
        Row(Modifier.fillMaxSize()) {
            NavRail(selected = screen, onSelect = { screen = it })
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                when (screen) {
                    Screen.Dashboard -> DashboardScreen()
                    Screen.Sensors -> SensorsScreen(onOpenConnection = { screen = Screen.Connection })
                    Screen.Connection -> ConnectionScreen(
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
                    Screen.Debug -> DebugScreen()
                }
            }
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

private val RailItemPadding = 4.dp
private val RailItemHeight = 88.dp
private val RailIconSize = 32.dp
private val RailIconStroke = 2.5.dp

/** Left rail: the selected item is a raised panel with ink text, not an accent colour. */
@Composable
private fun NavRail(selected: Screen, onSelect: (Screen) -> Unit) {
    val colors = KLineTheme.colors
    Column(
        modifier = Modifier
            .width(Spacing.railWidth)
            .fillMaxHeight()
            .background(colors.panel)
            .padding(horizontal = RailItemPadding, vertical = Spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(Spacing.grid),
    ) {
        Screen.entries.forEach { item ->
            val isSelected = item == selected
            val content = if (isSelected) colors.ink else colors.inkMuted
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(RailItemHeight)
                    .clip(RoundedCornerShape(Spacing.controlRadius))
                    .background(if (isSelected) colors.panelRaised else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(item) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                RailIcon(item, content)
                Spacer(Modifier.height(Spacing.grid / 2))
                Text(
                    stringResource(item.labelRes),
                    style = KLineType.label,
                    color = content,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Hand-drawn icons: material-icons is not a dependency and four glyphs do not justify it. */
@Composable
private fun RailIcon(screen: Screen, tint: Color) {
    Canvas(Modifier.size(RailIconSize)) {
        val w = size.width
        val strokeWidth = RailIconStroke.toPx()
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        when (screen) {
            Screen.Dashboard -> {
                drawArc(
                    color = tint,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(w * 0.08f, w * 0.08f),
                    size = Size(w * 0.84f, w * 0.84f),
                    style = stroke,
                )
                drawLine(tint, center, Offset(w * 0.72f, w * 0.3f), strokeWidth, StrokeCap.Round)
            }
            Screen.Sensors -> {
                drawLine(tint, Offset(w * 0.2f, w * 0.9f), Offset(w * 0.2f, w * 0.55f), strokeWidth * 2, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.5f, w * 0.9f), Offset(w * 0.5f, w * 0.15f), strokeWidth * 2, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.8f, w * 0.9f), Offset(w * 0.8f, w * 0.4f), strokeWidth * 2, StrokeCap.Round)
            }
            Screen.Connection -> {
                drawCircle(tint, radius = w * 0.16f, center = Offset(w * 0.2f, w * 0.5f))
                drawCircle(tint, radius = w * 0.16f, center = Offset(w * 0.8f, w * 0.5f))
                drawLine(tint, Offset(w * 0.3f, w * 0.5f), Offset(w * 0.7f, w * 0.5f), strokeWidth, StrokeCap.Round)
            }
            Screen.Debug -> {
                drawLine(tint, Offset(w * 0.15f, w * 0.3f), Offset(w * 0.4f, w * 0.5f), strokeWidth, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.4f, w * 0.5f), Offset(w * 0.15f, w * 0.7f), strokeWidth, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.5f, w * 0.75f), Offset(w * 0.85f, w * 0.75f), strokeWidth, StrokeCap.Round)
            }
        }
    }
}
