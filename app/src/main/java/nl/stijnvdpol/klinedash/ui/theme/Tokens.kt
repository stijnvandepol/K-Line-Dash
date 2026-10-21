package nl.stijnvdpol.klinedash.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The colour tokens of docs/design-system.md. A theme is only a different set of these values;
 * grid, type scale and the meaning of each colour never change (phase 5 adds themes this way).
 */
@Immutable
data class KLineColors(
    val background: Color,
    val panel: Color,
    val panelRaised: Color,
    val ink: Color,
    val inkMuted: Color,
    val line: Color,
    val ok: Color,
    val caution: Color,
    val danger: Color,
)

/** Default theme "Donker". Keep the background in sync with res/values/colors.xml (window_background). */
val DonkerColors = KLineColors(
    background = Color(0xFF101418),
    panel = Color(0xFF181E24),
    panelRaised = Color(0xFF222A32),
    ink = Color(0xFFE9EDF0),
    inkMuted = Color(0xFF8E9AA6),
    line = Color(0xFF2C353E),
    ok = Color(0xFF45C486),
    caution = Color(0xFFF2B33D),
    danger = Color(0xFFF0524F),
)

val LocalKLineColors = staticCompositionLocalOf { DonkerColors }

/** 8 dp grid, margins and sizes. Identical in every theme. */
object Spacing {
    val grid = 8.dp
    val screenMargin = 24.dp
    val gutter = 16.dp
    val panelPadding = 16.dp
    val heroPadding = 20.dp
    val railWidth = 104.dp
    val touchMin = 72.dp
    val listRowMin = 56.dp
    val panelRadius = 16.dp
    val controlRadius = 12.dp
    val dividerThickness = 1.dp
}
