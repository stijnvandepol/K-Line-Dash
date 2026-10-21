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
    val surface: Color,
    val rule: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val ok: Color,
    val caution: Color,
    val danger: Color,
)

/** Default theme "Race". Keep the background in sync with res/values/colors.xml (window_background). */
val RaceColors = KLineColors(
    background = Color(0xFF000000),
    surface = Color(0xFF1C1C1C),
    rule = Color(0xFF2A2A2A),
    ink = Color(0xFFFFFFFF),
    inkMuted = Color(0xFF8C8C8C),
    inkFaint = Color(0xFF4D4D4D),
    ok = Color(0xFF2ED47A),
    caution = Color(0xFFFFB000),
    danger = Color(0xFFFF2D2D),
)

val LocalKLineColors = staticCompositionLocalOf { RaceColors }

/** 8 dp grid and the sizes of the layout. Identical in every theme; there are no corner radii. */
object Spacing {
    val grid = 8.dp
    val fieldPaddingHorizontal = 16.dp
    val fieldPaddingVertical = 12.dp
    val touchMin = 72.dp
    val listRowMin = 56.dp
    val ruleThickness = 1.dp
    val markerThickness = 4.dp
}
