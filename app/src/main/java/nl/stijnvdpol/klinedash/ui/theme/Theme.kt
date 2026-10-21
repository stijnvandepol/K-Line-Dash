package nl.stijnvdpol.klinedash.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import nl.stijnvdpol.klinedash.core.elm.ConnectionState

/** Access to the active tokens: `KLineTheme.colors.ink`. */
object KLineTheme {
    val colors: KLineColors
        @Composable
        @ReadOnlyComposable
        get() = LocalKLineColors.current
}

/**
 * Material only supplies behaviour (ripples, switch, text field); every colour it draws comes from
 * the tokens. No accent colour exists, so the "primary" role is plain ink.
 */
private fun KLineColors.toMaterialScheme() = darkColorScheme(
    primary = ink,
    onPrimary = background,
    primaryContainer = panelRaised,
    onPrimaryContainer = ink,
    secondary = inkMuted,
    onSecondary = background,
    secondaryContainer = panelRaised,
    onSecondaryContainer = ink,
    tertiary = inkMuted,
    onTertiary = background,
    background = background,
    onBackground = ink,
    surface = panel,
    onSurface = ink,
    surfaceVariant = panelRaised,
    onSurfaceVariant = inkMuted,
    surfaceTint = Color.Transparent,
    outline = line,
    outlineVariant = line,
    error = danger,
    onError = background,
)

@Composable
fun KLineDashTheme(colors: KLineColors = DonkerColors, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalKLineColors provides colors) {
        MaterialTheme(colorScheme = colors.toMaterialScheme(), typography = MaterialTypography, content = content)
    }
}

/** Traffic-light colour for a connection state: green connected, red failed, amber busy, muted idle. */
fun ConnectionState.accentColor(colors: KLineColors): Color = when (this) {
    is ConnectionState.Connected -> colors.ok
    is ConnectionState.Failed -> colors.danger
    ConnectionState.Idle -> colors.inkMuted
    is ConnectionState.Connecting, is ConnectionState.Initializing, is ConnectionState.Reconnecting -> colors.caution
}
