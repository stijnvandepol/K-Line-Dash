package nl.stijnvdpol.klinedash.core.alert

import nl.stijnvdpol.klinedash.core.obd.PollTier
import nl.stijnvdpol.klinedash.core.obd.Signals

/**
 * The warning rules. Pure: time comes from [AlertInput.nowMs], everything remembered between calls
 * lives in [AlertMemory.values] (named keys below), and the previous [AlertState.active] list
 * provides the hysteresis ("already raised, so clear later").
 *
 * Readings older than a freshness window are ignored. The window depends on how often the signal is
 * polled ([PollTier]); on the K-line target car slow signals (battery, MIL, code count) refresh only
 * about every 15 s, so they get twice that. What an ignored reading means differs per rule:
 * coolant and voltage alerts clear (a condition that cannot be seen is not claimed), MIL and code
 * alerts are latched facts and are held until a fresh reading says otherwise.
 */
object AlertEvaluator : AlertRules {

    private const val FAST_FRESHNESS_MS = 10_000L
    private const val MEDIUM_FRESHNESS_MS = 15_000L
    private const val SLOW_FRESHNESS_MS = 30_000L

    /** Clear a coolant alert only this far below the threshold that raised it, so it does not flap. */
    internal const val COOLANT_HYSTERESIS_C = 2.0

    /** Clear a voltage alert only this far back inside the limit. */
    internal const val VOLTAGE_HYSTERESIS_V = 0.2

    /** A voltage condition must hold this long before it is raised (cranking dips, ATRV noise). */
    internal const val VOLTAGE_DEBOUNCE_MS = 10_000L

    /** Fresh engine speed at or above this means the engine is running (cranking is ~200-300 rpm). */
    internal const val ENGINE_RUNNING_RPM = 400.0

    /**
     * Danger distances from the user's limit; with the default limits they give the research doc's
     * alarm levels: running < 12.5 V or > 15.5 V, engine off < 11.5 V.
     */
    internal const val RUNNING_LOW_DANGER_MARGIN_V = 0.7
    internal const val RUNNING_HIGH_DANGER_MARGIN_V = 0.5
    internal const val ENGINE_OFF_LOW_DANGER_MARGIN_V = 0.5

    private const val MIL_ON_THRESHOLD = 0.5

    private const val MEMORY_VOLTAGE_LOW_SINCE = "voltage_low_since_ms"
    private const val MEMORY_VOLTAGE_HIGH_SINCE = "voltage_high_since_ms"
    private const val MEMORY_DTC_BASELINE = "dtc_baseline"
    private const val MEMORY_CONNECTED_ONCE = "connected_once"
    private const val FLAG_SET = 1.0

    private const val SIGNAL_COOLANT = "coolant"
    private const val SIGNAL_BATTERY = "battery"
    private const val SIGNAL_RPM = "rpm"
    private const val SIGNAL_SPEED = "speed"
    private const val SIGNAL_DTC_COUNT = "dtc_count"
    private const val SIGNAL_MIL = "mil_on"

    override fun requiredSignals(settings: AlertSettings): Set<String> =
        settings.enabled.flatMapTo(mutableSetOf()) { signalsFor(it) }

    private fun signalsFor(kind: AlertKind): Set<String> = when (kind) {
        AlertKind.COOLANT_HIGH -> setOf(SIGNAL_COOLANT)
        AlertKind.VOLTAGE_LOW, AlertKind.VOLTAGE_HIGH -> setOf(SIGNAL_BATTERY, SIGNAL_RPM)
        AlertKind.NEW_DTC -> setOf(SIGNAL_DTC_COUNT, SIGNAL_SPEED)
        AlertKind.MIL_ON -> setOf(SIGNAL_MIL)
        AlertKind.CONNECTION_LOST -> emptySet()
    }

    override fun evaluate(input: AlertInput, settings: AlertSettings, previous: AlertState): AlertState =
        Evaluation(input, settings, previous).run()

    internal fun freshnessWindowMs(signalId: String): Long =
        when (Signals.byId(signalId)?.defaultTier) {
            PollTier.SLOW -> SLOW_FRESHNESS_MS
            PollTier.MEDIUM -> MEDIUM_FRESHNESS_MS
            PollTier.FAST, null -> FAST_FRESHNESS_MS
        }

    /** One call of [evaluate]: collects the active alerts and the updated memory. */
    private class Evaluation(
        private val input: AlertInput,
        private val settings: AlertSettings,
        private val previous: AlertState,
    ) {
        private val memory = previous.memory.values.toMutableMap()
        private val alerts = mutableListOf<Alert>()

        fun run(): AlertState {
            for (kind in AlertKind.entries) {
                if (kind in settings.enabled) evaluateKind(kind)
            }
            return AlertState(alerts, newlyRaisedAlerts(), AlertMemory(memory))
        }

        private fun evaluateKind(kind: AlertKind) {
            when (kind) {
                AlertKind.COOLANT_HIGH -> evaluateCoolant()
                AlertKind.VOLTAGE_LOW -> evaluateVoltage(kind)
                AlertKind.VOLTAGE_HIGH -> evaluateVoltage(kind)
                AlertKind.NEW_DTC -> evaluateNewDtc()
                AlertKind.MIL_ON -> evaluateMil()
                AlertKind.CONNECTION_LOST -> evaluateConnection()
            }
        }

        private fun newlyRaisedAlerts(): List<Alert> = alerts.filter { alert ->
            val before = previousAlert(alert.kind)
            before == null || before.severity < alert.severity
        }

        private fun previousAlert(kind: AlertKind): Alert? = previous.active.firstOrNull { it.kind == kind }

        private fun freshValue(signalId: String): Double? {
            val reading = input.readings[signalId] ?: return null
            val ageMs = input.nowMs - reading.timestampMs
            return reading.value.takeIf { ageMs <= freshnessWindowMs(signalId) }
        }

        private fun raise(kind: AlertKind, severity: AlertSeverity, value: Double?, sinceMs: Long?) {
            alerts += Alert(kind, severity, value, sinceMs ?: input.nowMs)
        }

        private fun evaluateCoolant() {
            val celsius = freshValue(SIGNAL_COOLANT) ?: return
            val before = previousAlert(AlertKind.COOLANT_HIGH)
            val dangerLimit = settings.coolantDangerC -
                if (before?.severity == AlertSeverity.DANGER) COOLANT_HYSTERESIS_C else 0.0
            val cautionLimit = settings.coolantCautionC - if (before != null) COOLANT_HYSTERESIS_C else 0.0
            val severity = when {
                celsius >= dangerLimit -> AlertSeverity.DANGER
                celsius >= cautionLimit -> AlertSeverity.CAUTION
                else -> return
            }
            raise(AlertKind.COOLANT_HIGH, severity, celsius, before?.sinceMs)
        }

        /** Battery voltage: the limits depend on whether the engine is running (fresh rpm). */
        private fun evaluateVoltage(kind: AlertKind) {
            val timerKey = if (kind == AlertKind.VOLTAGE_LOW) MEMORY_VOLTAGE_LOW_SINCE else MEMORY_VOLTAGE_HIGH_SINCE
            val volts = freshValue(SIGNAL_BATTERY)
            val rpm = freshValue(SIGNAL_RPM)
            if (volts == null || rpm == null) {
                memory.remove(timerKey)
                return
            }
            val isRunning = rpm >= ENGINE_RUNNING_RPM
            when {
                kind == AlertKind.VOLTAGE_LOW -> evaluateLowVoltage(volts, isRunning, timerKey)
                isRunning -> evaluateHighVoltage(volts, timerKey)
                else -> memory.remove(timerKey)
            }
        }

        private fun evaluateLowVoltage(volts: Double, isRunning: Boolean, timerKey: String) {
            val limit = if (isRunning) settings.voltageRunningLow else settings.voltageEngineOffLow
            val dangerMargin = if (isRunning) RUNNING_LOW_DANGER_MARGIN_V else ENGINE_OFF_LOW_DANGER_MARGIN_V
            val severity = if (volts < limit - dangerMargin) AlertSeverity.DANGER else AlertSeverity.CAUTION
            debounce(
                AlertKind.VOLTAGE_LOW, timerKey, volts, severity,
                isTripped = volts < limit,
                isStillHeld = volts < limit + VOLTAGE_HYSTERESIS_V,
            )
        }

        private fun evaluateHighVoltage(volts: Double, timerKey: String) {
            val limit = settings.voltageRunningHigh
            val severity = if (volts > limit + RUNNING_HIGH_DANGER_MARGIN_V) AlertSeverity.DANGER else AlertSeverity.CAUTION
            debounce(
                AlertKind.VOLTAGE_HIGH, timerKey, volts, severity,
                isTripped = volts > limit,
                isStillHeld = volts > limit - VOLTAGE_HYSTERESIS_V,
            )
        }

        /**
         * Raises [kind] once [isTripped] has held for [VOLTAGE_DEBOUNCE_MS]; an alert that is
         * already active stays while [isStillHeld] (the hysteresis band) and then clears at once.
         */
        private fun debounce(
            kind: AlertKind,
            timerKey: String,
            value: Double,
            severity: AlertSeverity,
            isTripped: Boolean,
            isStillHeld: Boolean,
        ) {
            val before = previousAlert(kind)
            if (before != null && isStillHeld) {
                raise(kind, severity, value, before.sinceMs)
                return
            }
            if (!isTripped) {
                memory.remove(timerKey)
                return
            }
            val sinceMs = memory.getOrPut(timerKey) { input.nowMs.toDouble() }.toLong()
            if (input.nowMs - sinceMs >= VOLTAGE_DEBOUNCE_MS) raise(kind, severity, value, sinceMs)
        }

        /**
         * Active whenever the lamp is on, so a lamp that is already on at the first reading is
         * raised once (as a caution) like one that comes on later. A stale reading holds the
         * previous state.
         */
        private fun evaluateMil() {
            val before = previousAlert(AlertKind.MIL_ON)
            val milValue = freshValue(SIGNAL_MIL)
            when {
                milValue == null -> before?.let { alerts += it }
                milValue >= MIL_ON_THRESHOLD -> raise(AlertKind.MIL_ON, AlertSeverity.CAUTION, null, before?.sinceMs)
            }
        }

        /**
         * The baseline is the first fresh code count of the connection (codes that were already
         * stored are not news). A count above the baseline is raised once the car moves (fresh
         * speed above 0); codes set while parked are reported when driving starts. The alert stays
         * while the count is above the baseline; a lower count (codes cleared) lowers the baseline
         * and clears it. Losing the connection forgets the baseline and the alert.
         */
        private fun evaluateNewDtc() {
            if (!input.isConnected) {
                memory.remove(MEMORY_DTC_BASELINE)
                return
            }
            val before = previousAlert(AlertKind.NEW_DTC)
            val count = freshValue(SIGNAL_DTC_COUNT)
            if (count == null) {
                before?.let { alerts += it }
                return
            }
            val baseline = memory[MEMORY_DTC_BASELINE]
            if (baseline == null || count < baseline) {
                memory[MEMORY_DTC_BASELINE] = count
                return
            }
            val isDriving = (freshValue(SIGNAL_SPEED) ?: 0.0) > 0.0
            if (count > baseline && (before != null || isDriving)) {
                raise(AlertKind.NEW_DTC, AlertSeverity.CAUTION, null, before?.sinceMs)
            }
        }

        /** Only after the link has been up once; a car that was never reached is not a "lost" link. */
        private fun evaluateConnection() {
            if (input.isConnected) {
                memory[MEMORY_CONNECTED_ONCE] = FLAG_SET
                return
            }
            if (MEMORY_CONNECTED_ONCE !in memory) return
            raise(AlertKind.CONNECTION_LOST, AlertSeverity.DANGER, null, previousAlert(AlertKind.CONNECTION_LOST)?.sinceMs)
        }
    }
}
