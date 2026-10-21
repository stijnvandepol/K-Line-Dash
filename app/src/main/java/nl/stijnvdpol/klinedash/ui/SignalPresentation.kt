package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.core.obd.PollTier
import nl.stijnvdpol.klinedash.core.obd.Signal
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.service.formatNumber

/** Shown in a value slot when there is no reading yet; same slot, so nothing shifts. */
internal const val MISSING_VALUE = "--"

/** Signals that are a lamp or a flag (1 = on, 0 = off): shown as a word, not as a number. */
private val OnOffSignalIds = setOf(MIL_ID)

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

/** The words for an on/off signal; string resources in the UI. */
internal class OnOffWords(val on: String, val off: String)

internal fun formatReading(signal: Signal, value: Double, words: OnOffWords): String = when {
    signal.id !in OnOffSignalIds -> formatNumber(value, signal.decimals)
    value != 0.0 -> words.on
    else -> words.off
}

/**
 * The widest text a signal normally shows with every digit replaced by an "8" (all digits are the
 * same width with tabular figures). Measuring it gives the value slot a width that never changes.
 */
internal fun slotSample(signal: Signal): String {
    val widest = listOf(signal.min, signal.max).map { formatNumber(it, signal.decimals) }.maxBy { it.length }
    return widest.map { if (it.isDigit()) '8' else it }.joinToString("")
}

/** The ECU answers it (or it is an adapter command such as the battery voltage). */
internal fun Signal.isAvailable(supportedPids: Set<Int>): Boolean = pid == null || pid in supportedPids

internal fun supportedSignals(supportedPids: Set<Int>): List<Signal> =
    Signals.all.filter { it.isAvailable(supportedPids) }

/** What a value means for the driver; colour is the only thing that changes (docs/design-system.md). */
internal enum class ValueTone { NORMAL, CAUTION, DANGER }

private const val COOLANT_CAUTION_C = 100.0
private const val COOLANT_DANGER_C = 105.0
private const val BATTERY_LOW_V = 12.0
private const val BATTERY_HIGH_V = 15.0

/** Above cranking speed. Below it the battery voltage says nothing about the charging system. */
private const val ENGINE_RUNNING_RPM = 400.0

internal fun isEngineRunning(rpm: Double?): Boolean = rpm != null && rpm >= ENGINE_RUNNING_RPM

/**
 * Coolant from 100 degrees is caution, from 105 danger. The battery is caution below 12.0 V or
 * above 15.0 V, but only with the engine running. Every other signal has no limits yet (phase 4).
 */
internal fun valueTone(signalId: String, value: Double, engineRunning: Boolean): ValueTone = when (signalId) {
    COOLANT_ID -> when {
        value >= COOLANT_DANGER_C -> ValueTone.DANGER
        value >= COOLANT_CAUTION_C -> ValueTone.CAUTION
        else -> ValueTone.NORMAL
    }
    BATTERY_ID -> if (engineRunning && (value < BATTERY_LOW_V || value > BATTERY_HIGH_V)) ValueTone.CAUTION else ValueTone.NORMAL
    else -> ValueTone.NORMAL
}
