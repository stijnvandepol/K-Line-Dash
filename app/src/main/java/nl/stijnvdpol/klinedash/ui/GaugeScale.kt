package nl.stijnvdpol.klinedash.ui

/** Geometry of the RPM gauge, in degrees and rpm. Angles follow Compose: 0 = 3 o'clock, clockwise. */
internal object GaugeScale {
    /** Bottom-left; the 240 degree sweep ends bottom-right and leaves the bottom open. */
    const val START_ANGLE = 150f
    const val SWEEP_DEGREES = 240f

    /** Where the 2ZZ-GE limiter zone starts (limiter itself is about 8 200). */
    const val REDLINE_RPM = 7_800.0
    const val MAJOR_STEP_RPM = 1_000
    const val MINOR_STEP_RPM = 500

    /** Position on the scale from 0 (start) to 1 (end); out-of-range values stick to the ends. */
    fun fraction(value: Double, max: Double): Float = (value / max).coerceIn(0.0, 1.0).toFloat()

    fun angleFor(fraction: Float): Float = START_ANGLE + SWEEP_DEGREES * fraction
}

private const val MS_PER_SECOND = 1_000.0
private const val DEFAULT_NEEDLE_MS = 500
private const val MIN_NEEDLE_MS = 100
private const val MAX_NEEDLE_MS = 1_000

/**
 * How long the needle takes to travel to a new reading: the measured interval between rpm readings,
 * so it arrives just as the next one does and the motion never stops. [updatesPerSecond] is null or 0
 * until the poller has measured something.
 */
internal fun needleDurationMs(updatesPerSecond: Double?): Int {
    val intervalMs = updatesPerSecond?.takeIf { it > 0 }?.let { MS_PER_SECOND / it } ?: DEFAULT_NEEDLE_MS.toDouble()
    return intervalMs.toInt().coerceIn(MIN_NEEDLE_MS, MAX_NEEDLE_MS)
}
