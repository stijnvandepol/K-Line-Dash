package nl.stijnvdpol.klinedash.core.alert

import nl.stijnvdpol.klinedash.core.obd.Reading

/** The warnings the user asked for; each can be switched on or off. */
enum class AlertKind {
    COOLANT_HIGH,
    VOLTAGE_LOW,
    VOLTAGE_HIGH,

    /** A stored trouble code appeared while driving (PID 01 code count went up). */
    NEW_DTC,

    /** The check-engine light came on. */
    MIL_ON,

    /** The adapter link dropped after having been connected. */
    CONNECTION_LOST,
}

/** How urgent an [Alert] is; DANGER outranks CAUTION, so a rise from one to the other is raised anew. */
enum class AlertSeverity { CAUTION, DANGER }

/** One active warning. [value] is the reading that triggered it (null for MIL/DTC/connection). */
data class Alert(
    val kind: AlertKind,
    val severity: AlertSeverity,
    val value: Double?,
    /** When the condition started (System.currentTimeMillis()). */
    val sinceMs: Long,
)

/**
 * User-adjustable limits. Defaults come from docs/research/2zz-ge-obd.md section 4 (2ZZ-GE:
 * thermostat opens at ~82 °C, normal 85-95 °C; alternator regulates ~13.8-14.5 V).
 */
data class AlertSettings(
    val enabled: Set<AlertKind> = AlertKind.entries.toSet(),
    /** Kinds that also beep; the visual warning is always shown for enabled kinds. */
    val withSound: Set<AlertKind> = setOf(AlertKind.COOLANT_HIGH, AlertKind.MIL_ON, AlertKind.NEW_DTC),
    val coolantCautionC: Double = 103.0,
    val coolantDangerC: Double = 108.0,
    /** Battery limits with the engine running (alternator charging). */
    val voltageRunningLow: Double = 13.2,
    val voltageRunningHigh: Double = 15.0,
    /** Battery limit with the ignition on and the engine off. */
    val voltageEngineOffLow: Double = 12.0,
)

/** Everything the evaluator looks at for one evaluation. */
data class AlertInput(
    val readings: Map<String, Reading>,
    /** True while the adapter link is connected. */
    val isConnected: Boolean,
    val nowMs: Long,
)

/**
 * Decides which warnings are active. Pure and stateful through [AlertState]: the caller keeps the
 * returned state and passes it back on the next call. Implemented by [AlertEvaluator].
 */
interface AlertRules {
    /** Signal ids that must be polled for the enabled warnings (demanded by the service). */
    fun requiredSignals(settings: AlertSettings): Set<String>

    fun evaluate(input: AlertInput, settings: AlertSettings, previous: AlertState): AlertState
}

/**
 * Result of one evaluation. [active] is what the UI shows; [newlyRaised] are the alerts that
 * started in this evaluation (the service beeps for those with sound enabled).
 * [memory] is evaluator-private bookkeeping (baselines, debounce timers) carried between calls.
 */
data class AlertState(
    val active: List<Alert> = emptyList(),
    val newlyRaised: List<Alert> = emptyList(),
    val memory: AlertMemory = AlertMemory(),
)

/** Opaque evaluator bookkeeping; its fields are owned by [AlertEvaluator]. */
data class AlertMemory(
    val values: Map<String, Double> = emptyMap(),
)
