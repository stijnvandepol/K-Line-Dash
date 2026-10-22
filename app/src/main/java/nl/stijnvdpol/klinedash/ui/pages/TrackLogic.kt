package nl.stijnvdpol.klinedash.ui.pages

import nl.stijnvdpol.klinedash.core.obd.Reading

/** Highest rpm and speed since the last reset; null until a reading came in. */
internal data class Peaks(val maxRpm: Double? = null, val maxSpeed: Double? = null)

/**
 * Peak hold. The poller keeps the last reading of every signal, so the same old reading is seen
 * again with every update of any signal; [rpmSeenMs] and [speedSeenMs] are the timestamps already
 * counted, and only a newer reading can raise a peak. After a reset they are the timestamps of the
 * readings at that moment, so the peak of before the reset cannot come back.
 */
internal data class PeakHold(
    val peaks: Peaks = Peaks(),
    val rpmSeenMs: Long = 0,
    val speedSeenMs: Long = 0,
) {
    fun withReadings(rpm: Reading?, speed: Reading?): PeakHold {
        val newRpm = rpm?.takeIf { it.timestampMs > rpmSeenMs }
        val newSpeed = speed?.takeIf { it.timestampMs > speedSeenMs }
        if (newRpm == null && newSpeed == null) return this
        return PeakHold(
            peaks = Peaks(
                maxRpm = higher(peaks.maxRpm, newRpm?.value),
                maxSpeed = higher(peaks.maxSpeed, newSpeed?.value),
            ),
            rpmSeenMs = newRpm?.timestampMs ?: rpmSeenMs,
            speedSeenMs = newSpeed?.timestampMs ?: speedSeenMs,
        )
    }

    fun reset(rpm: Reading?, speed: Reading?): PeakHold =
        PeakHold(Peaks(), rpm?.timestampMs ?: rpmSeenMs, speed?.timestampMs ?: speedSeenMs)

    private fun higher(current: Double?, candidate: Double?): Double? = when {
        candidate == null -> current
        current == null -> candidate
        else -> maxOf(current, candidate)
    }
}

/** One full flash period at 4 Hz: on for half of it, off for the other half. */
internal const val FLASH_HALF_PERIOD_MS = 125L

/** True in the lit half of the flash period; [elapsedMs] counts from the moment the flash started (lit). */
internal fun isFlashLit(elapsedMs: Long): Boolean = (elapsedMs / FLASH_HALF_PERIOD_MS) % 2 == 0L

/**
 * Whether the shift light flashes. The bar has to have reached the shift point (it follows the
 * reading with a sweep, see RpmBar) and the engine must still be above it: when the measured rpm
 * drops below, the flash stops at once, without waiting for the bar to travel down. A stale reading
 * never flashes.
 */
internal fun isShiftLightOn(barRpm: Double, measuredRpm: Double?, isStale: Boolean, shiftRpm: Int): Boolean =
    !isStale && measuredRpm != null && measuredRpm >= shiftRpm && barRpm >= shiftRpm
