package nl.stijnvdpol.klinedash.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.core.elm.ConnectionState

/** Access to the active tokens: `KLineTheme.colors.ink`. */
object KLineTheme {
    val colors: KLineColors
        @Composable
        @ReadOnlyComposable
        get() = LocalKLineColors.current
}

/**
 * Material only supplies behaviour (ripples, text field); every colour it draws comes from the
 * tokens. No accent colour exists, so the "primary" role is plain ink.
 */
private fun KLineColors.toMaterialScheme() = darkColorScheme(
    primary = ink,
    onPrimary = background,
    primaryContainer = surface,
    onPrimaryContainer = ink,
    secondary = inkMuted,
    onSecondary = background,
    secondaryContainer = surface,
    onSecondaryContainer = ink,
    tertiary = inkMuted,
    onTertiary = background,
    background = background,
    onBackground = ink,
    surface = background,
    onSurface = ink,
    surfaceVariant = surface,
    onSurfaceVariant = inkMuted,
    surfaceTint = Color.Transparent,
    outline = rule,
    outlineVariant = rule,
    error = danger,
    onError = background,
)

/** Corner radius 0 everywhere, also in the Material components (Shapes wants corner-based shapes). */
private val SquareCorner = RoundedCornerShape(0.dp)

private val SquareShapes = Shapes(
    extraSmall = SquareCorner,
    small = SquareCorner,
    medium = SquareCorner,
    large = SquareCorner,
    extraLarge = SquareCorner,
)

@Composable
fun KLineDashTheme(colors: KLineColors = RaceColors, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalKLineColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterialScheme(),
            typography = MaterialTypography,
            shapes = SquareShapes,
            content = content,
        )
    }
}

/** Traffic-light colour for a connection state: green connected, red failed, amber busy, muted idle. */
fun ConnectionState.accentColor(colors: KLineColors): Color = when (this) {
    is ConnectionState.Connected -> colors.ok
    is ConnectionState.Failed -> colors.danger
    ConnectionState.Idle -> colors.inkMuted
    is ConnectionState.Connecting, is ConnectionState.Initializing, is ConnectionState.Reconnecting -> colors.caution
}
