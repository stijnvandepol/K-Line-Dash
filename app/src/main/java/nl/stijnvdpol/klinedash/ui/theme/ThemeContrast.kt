package nl.stijnvdpol.klinedash.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/** WCAG minimum for normal text; labels and units are read at a glance, so every theme must meet it. */
internal const val MIN_LABEL_CONTRAST = 4.5

/** WCAG minimum for large text, the bar for text drawn on a status colour (bold capitals at 16 sp and up). */
internal const val MIN_FILLED_TEXT_CONTRAST = 3.0

private const val LINEAR_THRESHOLD = 0.03928
private const val LINEAR_DIVISOR = 12.92
private const val GAMMA_OFFSET = 0.055
private const val GAMMA_DIVISOR = 1.055
private const val GAMMA = 2.4
private const val RED_WEIGHT = 0.2126
private const val GREEN_WEIGHT = 0.7152
private const val BLUE_WEIGHT = 0.0722
private const val CONTRAST_OFFSET = 0.05

private fun linearChannel(value: Float): Double {
    val c = value.toDouble()
    return if (c <= LINEAR_THRESHOLD) c / LINEAR_DIVISOR else ((c + GAMMA_OFFSET) / GAMMA_DIVISOR).pow(GAMMA)
}

/** Relative luminance of an sRGB colour (WCAG 2.x), 0 for black to 1 for white. */
internal fun relativeLuminance(color: Color): Double =
    RED_WEIGHT * linearChannel(color.red) + GREEN_WEIGHT * linearChannel(color.green) + BLUE_WEIGHT * linearChannel(color.blue)

/** WCAG contrast ratio of two colours, 1.0 (identical) to 21.0 (black on white); symmetric. */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + CONTRAST_OFFSET) / (minOf(la, lb) + CONTRAST_OFFSET)
}

/**
 * Text colour for a label drawn on [fill]: [preferred] (what the design system names for that
 * status) unless it is hard to read there in this theme, then [alternative].
 */
internal fun readableOn(fill: Color, preferred: Color, alternative: Color): Color =
    if (contrastRatio(fill, preferred) >= MIN_FILLED_TEXT_CONTRAST) preferred else alternative
