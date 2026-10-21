package nl.stijnvdpol.klinedash.ui.theme

import android.graphics.Typeface
import android.os.Build
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private const val CONDENSED_FAMILY = "sans-serif-condensed"
private const val MEDIUM_WEIGHT = 500
private const val TABULAR_FIGURES = "tnum"
private const val LINE_HEIGHT_FACTOR = 1.2f

/** Roboto Condensed Bold from the system (nothing to ship): numerals and field labels. */
private val CondensedBold: FontFamily by lazy { FontFamily(Typeface.create(CONDENSED_FAMILY, Typeface.BOLD)) }

/** Medium weight needs API 28; below that the regular cut is used. */
private val CondensedMedium: FontFamily by lazy {
    val regular = Typeface.create(CONDENSED_FAMILY, Typeface.NORMAL)
    FontFamily(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(regular, MEDIUM_WEIGHT, false) else regular,
    )
}

private fun numberStyle(family: FontFamily, weight: FontWeight, size: Int, letterSpacing: Float = 0f) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * LINE_HEIGHT_FACTOR).sp,
    letterSpacing = letterSpacing.em,
    fontFeatureSettings = TABULAR_FIGURES,
)

private fun textStyle(size: Int, lineHeight: Int, weight: FontWeight) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontFeatureSettings = TABULAR_FIGURES,
)

/** The type scale of docs/design-system.md. Colour is applied where the text is used. */
object KLineType {
    val heroValue = numberStyle(CondensedBold, FontWeight.Bold, size = 168, letterSpacing = -0.02f)
    val primaryValue = numberStyle(CondensedBold, FontWeight.Bold, size = 112)
    val fieldValue = numberStyle(CondensedBold, FontWeight.Bold, size = 56)
    val rowValue = numberStyle(CondensedMedium, FontWeight.Medium, size = 28)

    /** The only all-caps style: callers uppercase the text. */
    val fieldLabel = numberStyle(CondensedBold, FontWeight.Bold, size = 16, letterSpacing = 0.08f)
    val title = textStyle(size = 24, lineHeight = 32, weight = FontWeight.Medium)
    val body = textStyle(size = 20, lineHeight = 28, weight = FontWeight.Normal)
    val caption = textStyle(size = 16, lineHeight = 22, weight = FontWeight.Normal)
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )
}

/** Material components (text field, ripple) read their text from here; it maps onto [KLineType]. */
internal val MaterialTypography = Typography(
    headlineSmall = KLineType.title,
    titleLarge = KLineType.title,
    titleMedium = KLineType.body,
    titleSmall = KLineType.body,
    bodyLarge = KLineType.body,
    bodyMedium = KLineType.body,
    bodySmall = KLineType.caption,
    labelLarge = KLineType.fieldLabel,
    labelMedium = KLineType.fieldLabel,
    labelSmall = KLineType.fieldLabel,
)
