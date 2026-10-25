package nl.stijnvdpol.klinedash.service

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import nl.stijnvdpol.klinedash.core.alert.Alert
import nl.stijnvdpol.klinedash.core.alert.AlertInput
import nl.stijnvdpol.klinedash.core.alert.AlertRules
import nl.stijnvdpol.klinedash.core.alert.AlertSettings
import nl.stijnvdpol.klinedash.core.alert.AlertSeverity
import nl.stijnvdpol.klinedash.core.alert.AlertState
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.obd.ObdPoller
import nl.stijnvdpol.klinedash.core.obd.Reading

private const val TAG = "AlertMonitor"
private const val DEMAND_OWNER = "alerts"

/** Evaluations per second at most; readings arrive faster than a warning needs to react. */
private const val MIN_EVALUATION_INTERVAL_MS = 250L

/** Time-based rules (debounce, stale readings) need an evaluation even when no reading changed. */
private const val EVALUATION_TICK_MS = 1_000L

/** Plays the warning tone for an alert that just started. Implemented by [ToneGeneratorPlayer]. */
fun interface AlertTonePlayer {
    suspend fun play(severity: AlertSeverity)
}

/**
 * Warning tones from [ToneGenerator], so the app needs no audio assets.
 *
 * Stream decision: [AudioManager.STREAM_ALARM]. The media volume is often turned down or muted on
 * a head unit (other audio source, navigation prompts) and a warning that depends on it would be
 * missed; the alarm stream has its own volume and is not muted with the media. The drawback is that
 * a few head units route only the media stream to the amplifier; if the KW905 stays silent, change
 * [stream] to [AudioManager.STREAM_MUSIC] where this player is created.
 *
 * One short beep for CAUTION, a double beep for DANGER. Plays are not queued: a tone that is still
 * sounding makes a new request a no-op, so several alerts raised at once give one tone.
 */
class ToneGeneratorPlayer(private val stream: Int = AudioManager.STREAM_ALARM) : AlertTonePlayer {
    private val playing = Mutex()

    override suspend fun play(severity: AlertSeverity) {
        if (!playing.tryLock()) return
        try {
            val generator = try {
                ToneGenerator(stream, TONE_VOLUME_PERCENT)
            } catch (e: RuntimeException) {
                // ToneGenerator throws RuntimeException("Init failed") when audio is unavailable.
                Log.w(TAG, "No tone generator available, warning stays silent", e)
                return
            }
            try {
                val (tone, durationMs) = when (severity) {
                    AlertSeverity.CAUTION -> ToneGenerator.TONE_PROP_BEEP to CAUTION_TONE_MS
                    AlertSeverity.DANGER -> ToneGenerator.TONE_PROP_BEEP2 to DANGER_TONE_MS
                }
                generator.startTone(tone, durationMs)
                delay(durationMs + TONE_TAIL_MS)
            } finally {
                generator.release()
            }
        } finally {
            playing.unlock()
        }
    }

    private companion object {
        const val TONE_VOLUME_PERCENT = 90
        const val CAUTION_TONE_MS = 300
        const val DANGER_TONE_MS = 900
        const val TONE_TAIL_MS = 100L
    }
}

/**
 * Turns the poller's readings into warnings. One instance lives for one link: [ObdService] runs
 * [run] next to the poller and cancels it with the link.
 *
 * While running it demands [AlertRules.requiredSignals] on the poller as owner "alerts", evaluates
 * on every change of readings, connection or settings (at most 4 per second, plus a 1 s tick for
 * time-based rules), hands the active alerts to [publish] and plays one tone for the alerts that
 * were newly raised and have sound enabled. The visual warning is the UI's job.
 *
 * Time: the rules measure the age of readings and debounce times with the single clock in
 * [AlertInput.nowMs]. Readings are stamped with the wall [clock], which a head unit without a clock
 * chip steps at boot, so the rules get the monotonic [elapsed] clock and the readings re-stamped onto
 * it with the age they have now (see [onMonotonicClock]). [Alert.sinceMs] is therefore a monotonic
 * time: only good for comparing alerts with each other, not for display.
 */
class AlertMonitor(
    private val settings: Flow<AlertSettings>,
    private val rules: AlertRules,
    private val tonePlayer: AlertTonePlayer,
    private val publish: (List<Alert>) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {
    /** Runs until cancelled; the demand is released and the published alerts are cleared afterwards. */
    suspend fun run(poller: ObdPoller, connection: StateFlow<ConnectionState>): Unit = coroutineScope {
        try {
            launch {
                settings.map(rules::requiredSignals).distinctUntilChanged().collect { poller.demand(DEMAND_OWNER, it) }
            }
            evaluateContinuously(poller, connection)
        } finally {
            poller.release(DEMAND_OWNER)
            publish(emptyList())
        }
    }

    private suspend fun CoroutineScope.evaluateContinuously(poller: ObdPoller, connection: StateFlow<ConnectionState>) {
        var state = AlertState()
        // conflate(): while the collector pauses between evaluations only the latest inputs are kept.
        combine(poller.readings, connection, settings, ticker()) { readings, conn, currentSettings, _ ->
            val elapsedNowMs = elapsed()
            val input = AlertInput(readings.onMonotonicClock(clock(), elapsedNowMs), conn is ConnectionState.Connected, elapsedNowMs)
            Snapshot(input, currentSettings, conn is ConnectionState.Idle)
        }.conflate().collect { snapshot ->
            // Idle means the user disconnected (or never connected): not a lost link, so nothing to
            // warn about, and the next connection starts with fresh baselines.
            state = if (snapshot.isStoppedByUser) AlertState() else rules.evaluate(snapshot.input, snapshot.settings, state)
            publish(state.active)
            playTone(state, snapshot.settings)
            delay(MIN_EVALUATION_INTERVAL_MS)
        }
    }

    private class Snapshot(val input: AlertInput, val settings: AlertSettings, val isStoppedByUser: Boolean)

    private fun ticker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(EVALUATION_TICK_MS)
        }
    }

    private fun CoroutineScope.playTone(state: AlertState, currentSettings: AlertSettings) {
        val audible = state.newlyRaised.filter { it.kind in currentSettings.withSound }
        if (audible.isEmpty()) return
        val severity = if (audible.any { it.severity == AlertSeverity.DANGER }) AlertSeverity.DANGER else AlertSeverity.CAUTION
        launch { tonePlayer.play(severity) }
    }
}

/**
 * [this] with every timestamp moved from the wall clock ([wallNowMs]) onto the monotonic clock
 * ([elapsedNowMs]): each reading keeps the age it has at this moment. A later step of the wall clock
 * then cannot change an age or a debounce time that is measured from now on.
 */
internal fun Map<String, Reading>.onMonotonicClock(wallNowMs: Long, elapsedNowMs: Long): Map<String, Reading> =
    mapValues { (_, reading) -> reading.copy(timestampMs = elapsedNowMs - (wallNowMs - reading.timestampMs)) }
