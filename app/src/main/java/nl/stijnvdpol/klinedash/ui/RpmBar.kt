package nl.stijnvdpol.klinedash.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.data.RpmScale
import nl.stijnvdpol.klinedash.ui.pages.isFlashLit
import nl.stijnvdpol.klinedash.ui.pages.isShiftLightOn
import nl.stijnvdpol.klinedash.ui.theme.KLineColors
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.LocalScreenProfile
import nl.stijnvdpol.klinedash.ui.theme.Spacing
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Each segment of the bar and of the arc cluster's tachometer covers this much rpm. */
internal const val SEGMENT_RPM = 200

/** The scale numerals stand one per this many rpm. */
internal const val SCALE_STEP_RPM = 1_000
private const val SEGMENTS_PER_SCALE_STEP = SCALE_STEP_RPM / SEGMENT_RPM
private const val UNLIT_ZONE_ALPHA = 0.25f
private const val MS_PER_SECOND = 1_000.0
private const val DEFAULT_SWEEP_MS = 500
private const val MIN_SWEEP_MS = 100
private const val MAX_SWEEP_MS = 1_000

private val SegmentGap = 2.dp
private val ScaleGap = 4.dp
private val ScaleHeight = 20.dp
private val BarPaddingBottom = Spacing.grid

internal enum class RpmZone { NORMAL, CAUTION, DANGER }

/** Normal below the caution rpm, caution from there, danger from the redline. */
internal fun rpmZone(rpm: Int, scale: RpmScale): RpmZone = when {
    rpm >= scale.redlineRpm -> RpmZone.DANGER
    rpm >= scale.cautionRpm -> RpmZone.CAUTION
    else -> RpmZone.NORMAL
}

/** How many segments cover the scale: 40 for a 6 500 redline (scale to 8 000), 30 for 4 500. */
internal fun segmentCount(scale: RpmScale): Int = scale.maxRpm / SEGMENT_RPM

/** The numerals of the scale in thousands, 0 to 8 for a 6 500 redline. */
internal fun scaleNumerals(scale: RpmScale): IntRange = 0..scale.maxRpm / SCALE_STEP_RPM

/** A segment is lit as soon as the rpm passes its start, so 830 rpm lights five of them. */
internal fun litSegmentCount(rpm: Double, segments: Int): Int = ceil(rpm / SEGMENT_RPM).toInt().coerceIn(0, segments)

/**
 * How long the bar takes to travel to a new reading: the measured interval between rpm readings,
 * so it arrives just as the next one does and the motion never stops. [updatesPerSecond] is null or 0
 * until the poller has measured something.
 */
internal fun sweepDurationMs(updatesPerSecond: Double?): Int {
    val intervalMs = updatesPerSecond?.takeIf { it > 0 }?.let { MS_PER_SECOND / it } ?: DEFAULT_SWEEP_MS.toDouble()
    return intervalMs.toInt().coerceIn(MIN_SWEEP_MS, MAX_SWEEP_MS)
}

private fun KLineColors.zoneColor(zone: RpmZone): Color = when (zone) {
    RpmZone.NORMAL -> accent
    RpmZone.CAUTION -> caution
    RpmZone.DANGER -> danger
}

/**
 * Left edge and width of a segment in pixels. The gap is a whole number of pixels, so rounding the
 * edges keeps every gap identical; only the segments themselves differ by a pixel.
 */
private class SegmentGeometry(widthPx: Float, gapPx: Float, segments: Int) {
    private val segmentWidth = (widthPx - gapPx * (segments - 1)) / segments
    private val pitch = segmentWidth + gapPx

    fun left(index: Int): Float = (index * pitch).roundToInt().toFloat()
    fun width(index: Int): Float = (index * pitch + segmentWidth).roundToInt() - left(index)
}

/**
 * The shift light of the track page: the bar and the frame around the page flash while [isLit].
 * The bar decides when (it knows the rpm it shows), the page only draws the frame from this.
 */
@Stable
internal class ShiftLight(val shiftRpm: Int) {
    var isLit by mutableStateOf(false)
}

/**
 * The segmented RPM bar with its scale. The unlit bar and the scale are drawn once into their own
 * layer; the lit segments are an [Animatable] that travels to each new reading in about the time
 * between two readings and is read in the draw phase only, so a frame of movement redraws at most
 * one rectangle per 200 rpm of the scale (30 to 55) and recomposes nothing. The scale and the zones
 * are those of the car ([LocalRpmScale]).
 */
@Composable
internal fun RpmBar(
    reading: State<Reading?>,
    stale: State<Boolean>,
    sweepMs: State<Int>,
    modifier: Modifier = Modifier,
    shiftLight: ShiftLight? = null,
) {
    val lit = rememberGlide(reading, sweepMs)
    if (shiftLight != null) {
        LaunchedEffect(shiftLight) { runShiftLight(shiftLight, lit, reading, stale) }
    }
    val colors = KLineTheme.colors
    val measurer = rememberTextMeasurer()
    val description = stringResource(R.string.rpm_bar_description)
    val metrics = dashMetricsOf(LocalScreenProfile.current.tier)
    val barHeight = metrics.barHeight
    val rpmScale = LocalRpmScale.current
    val segments = segmentCount(rpmScale)
    Box(
        modifier
            .padding(
                start = Spacing.fieldPaddingHorizontal,
                end = Spacing.fieldPaddingHorizontal,
                top = metrics.barPaddingTop,
                bottom = BarPaddingBottom,
            )
            .height(barHeight + ScaleGap + ScaleHeight)
            .semantics { contentDescription = description.format(Locale.US, reading.value?.value?.roundToInt() ?: 0) },
    ) {
        // Static layer: its own render node, so the moving bar never makes it redraw.
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawWithCache {
                    val geometry = SegmentGeometry(size.width, SegmentGap.toPx().roundToInt().coerceAtLeast(1).toFloat(), segments)
                    val barPx = barHeight.toPx()
                    val scale = scaleNumerals(rpmScale).map { thousand -> measurer.measure(thousand.toString(), KLineType.fieldLabel) }
                    onDrawBehind {
                        for (i in 0 until segments) {
                            val topLeft = Offset(geometry.left(i), 0f)
                            val segment = Size(geometry.width(i), barPx)
                            drawRect(colors.surface, topLeft, segment)
                            val zone = rpmZone(i * SEGMENT_RPM, rpmScale)
                            if (zone != RpmZone.NORMAL) drawRect(colors.zoneColor(zone), topLeft, segment, UNLIT_ZONE_ALPHA)
                        }
                        val labelTop = barPx + ScaleGap.toPx()
                        scale.forEachIndexed { step, label ->
                            // The last label (the end of the scale) has no segment of its own: it ends where the bar ends.
                            val x = if (step == scale.lastIndex) size.width - label.size.width
                            else geometry.left(step * SEGMENTS_PER_SCALE_STEP)
                            drawText(label, colors.inkMuted, Offset(x, labelTop))
                        }
                    }
                },
        )
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val geometry = SegmentGeometry(size.width, SegmentGap.toPx().roundToInt().coerceAtLeast(1).toFloat(), segments)
                    val barPx = barHeight.toPx()
                    onDrawBehind {
                        // Flashing: every segment in danger, so the whole bar blinks, not just its lit part.
                        val isFlashing = shiftLight?.isLit == true
                        val count = if (isFlashing) segments else litSegmentCount(lit.value.toDouble(), segments)
                        val dimmed = stale.value
                        for (i in 0 until count) {
                            val color = when {
                                isFlashing -> colors.danger
                                dimmed -> colors.inkFaint
                                else -> colors.zoneColor(rpmZone(i * SEGMENT_RPM, rpmScale))
                            }
                            drawRect(color, Offset(geometry.left(i), 0f), Size(geometry.width(i), barPx))
                        }
                    }
                },
        )
    }
}

/**
 * Flashes [shiftLight] at 4 Hz for as long as [isShiftLightOn] holds. It is evaluated on the value
 * the bar shows, so the flash starts when the bar reaches the shift point, and on the measured
 * reading, so it ends in the frame the rpm falls below it, not when the bar has travelled down.
 */
private suspend fun runShiftLight(
    shiftLight: ShiftLight,
    bar: Animatable<Float, *>,
    reading: State<Reading?>,
    stale: State<Boolean>,
) {
    snapshotFlow { isShiftLightOn(bar.value.toDouble(), reading.value?.value, stale.value, shiftLight.shiftRpm) }
        .distinctUntilChanged()
        .collectLatest { isOn ->
            if (!isOn) {
                shiftLight.isLit = false
                return@collectLatest
            }
            val startMs = withFrameMillis { it }
            while (true) withFrameMillis { frameMs -> shiftLight.isLit = isFlashLit(frameMs - startMs) }
        }
}
