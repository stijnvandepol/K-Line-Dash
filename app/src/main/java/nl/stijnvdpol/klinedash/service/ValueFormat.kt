package nl.stijnvdpol.klinedash.service

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToLong

/** Fixed, so values read the same whatever the head unit's (often Chinese) system locale is. */
private val UiLocale: Locale = Locale.US
private const val INTEGER_PATTERN = "#,##0"

/**
 * The one place that turns a number into text for the UI: decimal point, comma as thousands
 * separator, never "-0".
 */
fun formatNumber(value: Double, decimals: Int): String {
    val pattern = if (decimals > 0) "$INTEGER_PATTERN.${"0".repeat(decimals)}" else INTEGER_PATTERN
    val scale = 10.0.pow(decimals)
    // Rounding first turns -0.04 into 0.0 (adding 0.0 also clears negative zero), so no "-0.0".
    val rounded = (value * scale).roundToLong() / scale + 0.0
    return DecimalFormat(pattern, DecimalFormatSymbols(UiLocale)).format(rounded)
}

/** "13.9" */
fun formatVoltage(volts: Double): String = formatNumber(volts, 1)
