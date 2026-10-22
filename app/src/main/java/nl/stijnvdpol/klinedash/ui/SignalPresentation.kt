package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.core.alert.AlertSettings
import nl.stijnvdpol.klinedash.core.obd.PollTier
import nl.stijnvdpol.klinedash.core.obd.Signal
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.data.UnitSystem
import nl.stijnvdpol.klinedash.service.display
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

/** A reading ([value] is metric) as the text of a value slot in [units]. */
internal fun formatReading(signal: Signal, value: Double, words: OnOffWords, units: UnitSystem): String = when {
    signal.id !in OnOffSignalIds -> signal.display(units).format(value)
    value != 0.0 -> words.on
    else -> words.off
}

/** The unit text next to a value of [signal] in [units]: "mph" for a speed, "°F" for a temperature. */
internal fun unitOf(signal: Signal, units: UnitSystem): String = signal.display(units).unit

/**
 * The widest text a signal normally shows with every digit replaced by an "8" (all digits are the
 * same width with tabular figures). Measuring it gives the value slot a width that never changes.
 */
internal fun slotSample(signal: Signal, units: UnitSystem): String {
    val display = signal.display(units)
    val widest = listOf(display.min, display.max).map { formatNumber(it, display.decimals) }.maxBy { it.length }
    return widest.map { if (it.isDigit()) '8' else it }.joinToString("")
}

/** The ECU answers it (or it is an adapter command such as the battery voltage). */
internal fun Signal.isAvailable(supportedPids: Set<Int>): Boolean = pid == null || pid in supportedPids

/**
 * A signal this version knows and the car reports; while the supported PIDs are unknown (null) every
 * known signal counts. What the poller fetches and what the editor shows undimmed.
 */
internal fun isKnownAndSupported(signalId: String, supportedPids: Set<Int>?): Boolean {
    val signal = Signals.byId(signalId) ?: return false
    return supportedPids == null || signal.isAvailable(supportedPids)
}

internal fun supportedSignals(supportedPids: Set<Int>): List<Signal> =
    Signals.all.filter { it.isAvailable(supportedPids) }

/** What a value means for the driver; colour is the only thing that changes (docs/design-system.md). */
internal enum class ValueTone { NORMAL, CAUTION, DANGER }

/** Above cranking speed. Below it the battery voltage says nothing about the charging system. */
private const val ENGINE_RUNNING_RPM = 400.0

internal fun isEngineRunning(rpm: Double?): Boolean = rpm != null && rpm >= ENGINE_RUNNING_RPM

/** What decides a value's colour besides the value itself: the engine state and the warning limits. */
internal data class ToneContext(val engineRunning: Boolean, val limits: AlertSettings)

/**
 * The colour of a value follows the warning limits of SETUP > Warnings ([AlertSettings] is the one
 * source): coolant from the caution limit is caution, from the danger limit danger. The battery is
 * caution outside the running limits while the engine runs and below the engine-off limit while it
 * does not (a charging voltage means nothing then). Every other signal has no limits.
 */
internal fun valueTone(signalId: String, value: Double, context: ToneContext): ValueTone {
    val limits = context.limits
    return when (signalId) {
        COOLANT_ID -> when {
            value >= limits.coolantDangerC -> ValueTone.DANGER
            value >= limits.coolantCautionC -> ValueTone.CAUTION
            else -> ValueTone.NORMAL
        }
        BATTERY_ID -> if (isBatteryOutsideLimits(value, context)) ValueTone.CAUTION else ValueTone.NORMAL
        else -> ValueTone.NORMAL
    }
}

private fun isBatteryOutsideLimits(volts: Double, context: ToneContext): Boolean {
    val limits = context.limits
    return if (context.engineRunning) {
        volts < limits.voltageRunningLow || volts > limits.voltageRunningHigh
    } else {
        volts < limits.voltageEngineOffLow
    }
}
