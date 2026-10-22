package nl.stijnvdpol.klinedash.ui.cluster

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.obd.Signal
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.ui.LiveState
import nl.stijnvdpol.klinedash.ui.ValueTone
import nl.stijnvdpol.klinedash.ui.rememberGlide
import nl.stijnvdpol.klinedash.ui.rememberIsStale
import nl.stijnvdpol.klinedash.ui.rememberReading
import nl.stijnvdpol.klinedash.ui.rememberValueTone
import nl.stijnvdpol.klinedash.ui.sweepDurationMs

/**
 * One signal as a cluster draws it: the reading, whether it is stale, the tone of its value and,
 * for the fast signals, the glide that moves needles and arcs. All States, read late: the draw
 * phase reads [drawnValue], only the small text composables read the others.
 */
@Stable
internal class GaugeFeed(
    val signal: Signal,
    val reading: State<Reading?>,
    val stale: State<Boolean>,
    val tone: State<ValueTone>,
    private val glide: Animatable<Float, AnimationVector1D>?,
) {
    /** What a needle or arc shows right now: the gliding value, or the last reading for signals that do not glide. 0 without one. */
    fun drawnValue(): Float = glide?.value ?: (reading.value?.value?.toFloat() ?: 0f)
}

/** The feed of signal [id]; [glides] for rpm and speed, whose needles must move steadily between readings. */
@Composable
internal fun rememberGaugeFeed(live: LiveState, id: String, glides: Boolean): GaugeFeed {
    val signal = remember(id) { checkNotNull(Signals.byId(id)) }
    val reading = rememberReading(live.readings, id)
    val stale = rememberIsStale(reading, live.nowMs, signal)
    val tone = rememberValueTone(signal, reading, live.toneContext)
    val sweepMs = remember(live.stats, id) { derivedStateOf { sweepDurationMs(live.stats.value.signalHz[id]) } }
    val glide = if (glides) rememberGlide(reading, sweepMs) else null
    return remember(signal, reading, stale, tone, glide) { GaugeFeed(signal, reading, stale, tone, glide) }
}
