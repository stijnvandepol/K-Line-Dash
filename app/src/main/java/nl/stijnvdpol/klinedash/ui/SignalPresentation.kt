package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.core.obd.PollTier
import nl.stijnvdpol.klinedash.core.obd.Signal
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.service.formatNumber

/** Shown in a value slot when there is no reading yet; same slot, so nothing shifts. */
internal const val MISSING_VALUE = "–"

/**
 * A reading older than this is dimmed. The design asks for about 5 s; that holds for FAST signals.
 * MEDIUM and SLOW signals are polled every 3 s and 15 s (PollerConfig) and share one request slot
 * per round, so a healthy value is routinely older than 5 s and would flicker between ink and muted.
 */
private const val STALE_AFTER_FAST_MS = 5_000L
private const val STALE_AFTER_MEDIUM_MS = 15_000L
private const val STALE_AFTER_SLOW_MS = 45_000L

internal fun staleAfterMs(tier: PollTier): Long = when (tier) {
    PollTier.FAST -> STALE_AFTER_FAST_MS
    PollTier.MEDIUM -> STALE_AFTER_MEDIUM_MS
    PollTier.SLOW -> STALE_AFTER_SLOW_MS
}

internal fun isStale(signal: Signal, readingTimestampMs: Long, nowMs: Long): Boolean =
    nowMs - readingTimestampMs > staleAfterMs(signal.defaultTier)

internal fun formatReading(signal: Signal, value: Double): String = formatNumber(value, signal.decimals)

/**
 * The widest text a signal normally shows with every digit replaced by an "8" (all digits are the
 * same width with tabular figures). Measuring it gives the value slot a width that never changes.
 */
internal fun slotSample(signal: Signal): String {
    val widest = listOf(signal.min, signal.max).map { formatReading(signal, it) }.maxBy { it.length }
    return widest.map { if (it.isDigit()) '8' else it }.joinToString("")
}

/** The ECU answers it (or it is an adapter command such as the battery voltage). */
internal fun Signal.isAvailable(supportedPids: Set<Int>): Boolean = pid == null || pid in supportedPids

internal fun supportedSignals(supportedPids: Set<Int>): List<Signal> =
    Signals.all.filter { it.isAvailable(supportedPids) }
