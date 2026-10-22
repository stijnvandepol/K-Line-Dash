package nl.stijnvdpol.klinedash.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.data.ClusterStyle

/**
 * The colour tokens of docs/design-system.md. A theme is only a different set of these values;
 * grid, type scale and the meaning of each colour never change (see docs/themes.md).
 * [accent] colours the lit RPM segments below the caution zone and the active-page marker.
 * The last four are for the dial styles of docs/cluster-styles.md and follow the base tokens when a
 * theme does not set them: [face] fills a dial, [faceInk] draws its ticks and numerals, [ring] is
 * its rim and track, [needle] is the needle and the lit arc.
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
    val accent: Color,
    val face: Color = background,
    val faceInk: Color = ink,
    val ring: Color = rule,
    val needle: Color = accent,
)

/** The typeface of the numerals on the dials (docs/themes.md, key `numerals`). */
enum class NumeralFace(val id: String) {
    CONDENSED("condensed"),
    SERIF("serif"),
    LIGHT("light"),
    ;

    companion object {
        fun fromId(id: String): NumeralFace? = entries.firstOrNull { it.id == id }
    }
}

/** What a theme besides its colours brings to the Main page: the cluster style in effect and the numerals of its dials. */
@Immutable
data class ClusterLook(val style: ClusterStyle = ClusterStyle.BAR, val numerals: NumeralFace = NumeralFace.CONDENSED)

val LocalClusterLook = staticCompositionLocalOf { ClusterLook() }

/**
 * Default theme "Race", also the fallback while no theme is loaded. A test keeps it identical to
 * assets/themes/race.json; keep the background in sync with res/values/colors.xml (window_background).
 */
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
    accent = Color(0xFFFFFFFF),
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
