package nl.stijnvdpol.klinedash.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import nl.stijnvdpol.klinedash.core.elm.ConnectionState

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DD0E1),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF97F0FF),
    secondary = Color(0xFFFFB74D),
    onSecondary = Color(0xFF3F2800),
    // NavigationRail selection indicator.
    secondaryContainer = Color(0xFF004F58),
    onSecondaryContainer = Color(0xFF97F0FF),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE3E8EC),
    surface = Color(0xFF11171D),
    onSurface = Color(0xFFE3E8EC),
    surfaceVariant = Color(0xFF1C252D),
    onSurfaceVariant = Color(0xFFB0BCC6),
    outline = Color(0xFF52606B),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0000),
)

// Readable at arm's length on a 9-10 inch head unit: nothing below 16sp, body text 18-20sp.
private val base = Typography()
private val AppTypography = Typography(
    headlineLarge = base.headlineLarge.copy(fontSize = 44.sp, lineHeight = 52.sp),
    headlineMedium = base.headlineMedium.copy(fontSize = 36.sp, lineHeight = 44.sp),
    headlineSmall = base.headlineSmall.copy(fontSize = 30.sp, lineHeight = 38.sp),
    titleLarge = base.titleLarge.copy(fontSize = 26.sp, lineHeight = 34.sp),
    titleMedium = base.titleMedium.copy(fontSize = 22.sp, lineHeight = 30.sp),
    titleSmall = base.titleSmall.copy(fontSize = 20.sp, lineHeight = 28.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 20.sp, lineHeight = 28.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 18.sp, lineHeight = 26.sp),
    bodySmall = base.bodySmall.copy(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = base.labelLarge.copy(fontSize = 20.sp, lineHeight = 26.sp),
    labelMedium = base.labelMedium.copy(fontSize = 17.sp, lineHeight = 22.sp),
    labelSmall = base.labelSmall.copy(fontSize = 16.sp, lineHeight = 20.sp),
)

@Composable
fun KLineDashTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = AppTypography, content = content)
}

private val ConnectedGreen = Color(0xFF66BB6A)

/** Traffic-light colour for a connection state. */
@Composable
fun ConnectionState.accentColor(): Color = when (this) {
    is ConnectionState.Connected -> ConnectedGreen
    is ConnectionState.Failed -> MaterialTheme.colorScheme.error
    ConnectionState.Idle -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.secondary
}
