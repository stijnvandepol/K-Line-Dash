package nl.stijnvdpol.klinedash.service

import nl.stijnvdpol.klinedash.core.obd.Reading

/** Engine speed from which the engine counts as running (cranking is about 200-300 rpm). */
internal const val ENGINE_RUNNING_RPM = 400.0

/**
 * The RPM reading must be this fresh: the poller keeps the last value of a signal that gets
 * NO DATA, so an engine that stopped would otherwise look running forever.
 */
internal const val ENGINE_READING_FRESH_MS = 10_000L

/** How long the engine (or the link) may be gone before an automatic recording ends. */
internal const val AUTO_STOP_GRACE_MS = 2 * 60 * 1000L

private const val RPM_SIGNAL_ID = "rpm"

/** True when [readings] hold a fresh RPM at or above [ENGINE_RUNNING_RPM]. */
internal fun isEngineRunning(readings: Map<String, Reading>, nowMs: Long): Boolean {
    val rpm = readings[RPM_SIGNAL_ID] ?: return false
    return nowMs - rpm.timestampMs <= ENGINE_READING_FRESH_MS && rpm.value >= ENGINE_RUNNING_RPM
}

/** Who started the recording that is running. */
internal enum class RecordingOrigin { AUTO, MANUAL }

internal enum class AutoRecordAction { NONE, START, STOP }

/**
 * The automatic start/stop rule as a small state machine; it does no I/O and has no clock, so it
 * can be tested exactly. Called once per sample tick.
 *
 * - Nothing recording, auto-record on, engine running: [AutoRecordAction.START].
 * - An automatic recording is stopped once the engine has been off (or the link gone) for
 *   [graceMs] without interruption, so a stall or a short stop does not cut the trip in two.
 * - A manual recording is never stopped by this rule: the user ends it.
 * - After the user stopped a recording (or a start failed) no automatic start follows until the
 *   engine has been seen off once, otherwise stopping a trip with the engine running would start
 *   the next one a second later.
 */
internal class AutoRecordPolicy(private val graceMs: Long = AUTO_STOP_GRACE_MS) {
    private var engineOffSinceMs: Long? = null
    private var isStartSuppressed = false

    fun decide(
        nowMs: Long,
        isEngineRunning: Boolean,
        isAutoRecordEnabled: Boolean,
        recording: RecordingOrigin?,
    ): AutoRecordAction {
        if (isEngineRunning) {
            engineOffSinceMs = null
        } else {
            isStartSuppressed = false
            if (engineOffSinceMs == null) engineOffSinceMs = nowMs
        }
        return when (recording) {
            null -> if (isAutoRecordEnabled && isEngineRunning && !isStartSuppressed) AutoRecordAction.START else AutoRecordAction.NONE
            RecordingOrigin.MANUAL -> AutoRecordAction.NONE
            RecordingOrigin.AUTO -> if (isGraceOver(nowMs)) AutoRecordAction.STOP else AutoRecordAction.NONE
        }
    }

    /** Called when a recording ended for another reason than the grace period. */
    fun suppressStartUntilEngineStops() {
        isStartSuppressed = true
    }

    private fun isGraceOver(nowMs: Long): Boolean {
        val offSince = engineOffSinceMs ?: return false
        return nowMs - offSince >= graceMs
    }
}
