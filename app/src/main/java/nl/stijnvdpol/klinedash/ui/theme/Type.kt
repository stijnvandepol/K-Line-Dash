package nl.stijnvdpol.klinedash.ui.theme

import android.graphics.Typeface
import android.os.Build
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private const val CONDENSED_FAMILY = "sans-serif-condensed"
private const val MEDIUM_WEIGHT = 500
private const val TABULAR_FIGURES = "tnum"

/**
 * Roboto Condensed from the system (nothing to ship). Medium weight needs API 28; below that the
 * regular cut is used.
 */
private fun condensedTypeface(): Typeface {
    val regular = Typeface.create(CONDENSED_FAMILY, Typeface.NORMAL)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Typeface.create(regular, MEDIUM_WEIGHT, false)
    } else {
        regular
    }
}

private val NumberFont: FontFamily by lazy { FontFamily(condensedTypeface()) }

private fun numberStyle(size: Int, lineHeight: Int) = TextStyle(
    fontFamily = NumberFont,
    fontWeight = FontWeight.Medium,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontFeatureSettings = TABULAR_FIGURES,
)

private fun textStyle(size: Int, lineHeight: Int, weight: FontWeight) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
)

/** The type scale of docs/design-system.md. Colour is applied where the text is used. */
object KLineType {
    val heroValue = numberStyle(size = 104, lineHeight = 112)
    val largeValue = numberStyle(size = 56, lineHeight = 64)
    val tileValue = numberStyle(size = 40, lineHeight = 48)

    /** Sensor table values: tileValue is too big for a 56 dp row. */
    val rowValue = numberStyle(size = 26, lineHeight = 32)
    val title = textStyle(size = 26, lineHeight = 34, weight = FontWeight.Medium)
    val body = textStyle(size = 20, lineHeight = 28, weight = FontWeight.Normal)
    val label = textStyle(size = 16, lineHeight = 22, weight = FontWeight.Medium)
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )
}

/** Material components (switch, text field, button) read their text from here; it maps onto [KLineType]. */
internal val MaterialTypography = Typography(
    headlineSmall = KLineType.title,
    titleLarge = KLineType.title,
    titleMedium = KLineType.body,
    titleSmall = KLineType.body,
    bodyLarge = KLineType.body,
    bodyMedium = KLineType.body,
    bodySmall = KLineType.label,
    labelLarge = KLineType.body.copy(fontWeight = FontWeight.Medium),
    labelMedium = KLineType.label,
    labelSmall = KLineType.label,
)
