package nl.stijnvdpol.klinedash.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.service.HeadUnitPlatform
import nl.stijnvdpol.klinedash.service.detectPlatform
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private const val TAG = "StartupSettings"

/** What Android currently allows this app; read again whenever the user returns from a system screen. */
internal class SystemAccess(val canDrawOverlays: Boolean, val ignoresBatteryOptimizations: Boolean)

internal fun readSystemAccess(context: Context): SystemAccess = SystemAccess(
    canDrawOverlays = Settings.canDrawOverlays(context),
    ignoresBatteryOptimizations = context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName),
)

internal fun overlaySettingsIntent(context: Context) =
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())

// A sideloaded head-unit app: the Play Store policy the BatteryLife check guards does not apply.
@SuppressLint("BatteryLife")
internal fun batterySettingsIntent(context: Context) =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())

/** Some head-unit builds have no such settings screen; false then, so the caller can say so. */
internal fun openSystemScreen(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    Log.w(TAG, "No screen for ${intent.action}", e)
    false
}

@StringRes
private fun platformName(platform: HeadUnitPlatform): Int = when (platform) {
    HeadUnitPlatform.FYT -> R.string.platform_fyt
    HeadUnitPlatform.MTC -> R.string.platform_mtc
    HeadUnitPlatform.GENERIC -> R.string.platform_generic
}

@StringRes
private fun platformInstructions(platform: HeadUnitPlatform): Int = when (platform) {
    HeadUnitPlatform.FYT -> R.string.startup_alive_fyt
    HeadUnitPlatform.MTC -> R.string.startup_alive_mtc
    HeadUnitPlatform.GENERIC -> R.string.startup_alive_generic
}

/**
 * "Keep the app alive": head units kill apps that are not on their own whitelist shortly after the
 * ignition goes off, so this says where that list is for the detected platform and links to the two
 * Android permissions that help.
 */
@Composable
internal fun KeepAliveSection(access: SystemAccess) {
    val context = LocalContext.current
    val platform = remember { detectPlatform(context) }
    var failed by remember { mutableStateOf(false) }
    val colors = KLineTheme.colors
    val margin = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal)
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.startup_alive_title))
        Text(
            stringResource(R.string.startup_platform_label, stringResource(platformName(platform))),
            style = KLineType.body, color = colors.ink, modifier = margin,
        )
        Text(
            stringResource(platformInstructions(platform)),
            style = KLineType.caption, color = colors.inkMuted, modifier = margin.padding(top = Spacing.grid),
        )
        Text(
            stringResource(if (access.ignoresBatteryOptimizations) R.string.startup_battery_off else R.string.startup_battery_on),
            style = KLineType.caption, color = colors.inkMuted, modifier = margin.padding(top = Spacing.grid),
        )
        Row(
            Modifier.fillMaxWidth().padding(Spacing.fieldPaddingHorizontal).height(Spacing.touchMin),
            horizontalArrangement = Arrangement.spacedBy(Spacing.fieldPaddingHorizontal),
        ) {
            SecondaryButton(
                stringResource(R.string.btn_battery_optimization),
                { failed = !openSystemScreen(context, batterySettingsIntent(context)) },
                Modifier.weight(1f).height(Spacing.touchMin),
            )
            SecondaryButton(
                stringResource(R.string.btn_overlay_permission),
                { failed = !openSystemScreen(context, overlaySettingsIntent(context)) },
                Modifier.weight(1f).height(Spacing.touchMin),
            )
        }
        if (failed) {
            Text(
                stringResource(R.string.startup_settings_unavailable),
                style = KLineType.caption, color = colors.danger, modifier = margin.padding(bottom = Spacing.fieldPaddingHorizontal),
            )
        }
    }
}
