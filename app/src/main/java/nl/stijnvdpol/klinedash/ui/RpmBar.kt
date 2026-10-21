package nl.stijnvdpol.klinedash.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
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
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.ui.theme.KLineColors
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

internal const val RPM_BAR_SEGMENTS = 45
internal const val SEGMENT_RPM = 200

/** Where the 2ZZ-GE VVTL-i high cam engages, and where the limiter zone starts (limiter about 8 200). */
internal const val CAUTION_RPM = 6_200
internal const val DANGER_RPM = 7_800

private const val SCALE_STEP_RPM = 1_000
private const val SEGMENTS_PER_SCALE_STEP = SCALE_STEP_RPM / SEGMENT_RPM
private const val UNLIT_ZONE_ALPHA = 0.25f
private const val MS_PER_SECOND = 1_000.0
private const val DEFAULT_SWEEP_MS = 500
private const val MIN_SWEEP_MS = 100
private const val MAX_SWEEP_MS = 1_000

private val BarHeight = 96.dp
private val SegmentGap = 2.dp
private val ScaleGap = 4.dp
private val ScaleHeight = 20.dp
private val BarPaddingTop = 12.dp
private val BarPaddingBottom = 8.dp

internal enum class RpmZone { NORMAL, CAUTION, DANGER }

internal fun rpmZone(rpm: Int): RpmZone = when {
    rpm >= DANGER_RPM -> RpmZone.DANGER
    rpm >= CAUTION_RPM -> RpmZone.CAUTION
    else -> RpmZone.NORMAL
}

/** A segment is lit as soon as the rpm passes its start, so 830 rpm lights five of them. */
internal fun litSegmentCount(rpm: Double): Int = ceil(rpm / SEGMENT_RPM).toInt().coerceIn(0, RPM_BAR_SEGMENTS)

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
    RpmZone.NORMAL -> ink
    RpmZone.CAUTION -> caution
    RpmZone.DANGER -> danger
}

/**
 * Left edge and width of a segment in pixels. The gap is a whole number of pixels, so rounding the
 * edges keeps every gap identical; only the segments themselves differ by a pixel.
 */
private class SegmentGeometry(widthPx: Float, gapPx: Float) {
    private val segmentWidth = (widthPx - gapPx * (RPM_BAR_SEGMENTS - 1)) / RPM_BAR_SEGMENTS
    private val pitch = segmentWidth + gapPx

    fun left(index: Int): Float = (index * pitch).roundToInt().toFloat()
    fun width(index: Int): Float = (index * pitch + segmentWidth).roundToInt() - left(index)
}

/**
 * The segmented RPM bar with its scale. The unlit bar and the scale are drawn once into their own
 * layer; the lit segments are an [Animatable] that travels to each new reading in about the time
 * between two readings and is read in the draw phase only, so a frame of movement redraws at most
 * 45 rectangles and recomposes nothing.
 */
@Composable
internal fun RpmBar(
    reading: State<Reading?>,
    stale: State<Boolean>,
    sweepMs: State<Int>,
    modifier: Modifier = Modifier,
) {
    val lit = remember { Animatable(0f) }
    LaunchedEffect(lit) {
        snapshotFlow { reading.value?.value ?: 0.0 }.collectLatest { target ->
            lit.animateTo(target.toFloat(), tween(sweepMs.value, easing = LinearEasing))
        }
    }
    val colors = KLineTheme.colors
    val measurer = rememberTextMeasurer()
    val description = stringResource(R.string.rpm_bar_description)
    Box(
        modifier
            .padding(
                start = Spacing.fieldPaddingHorizontal,
                end = Spacing.fieldPaddingHorizontal,
                top = BarPaddingTop,
                bottom = BarPaddingBottom,
            )
            .height(BarHeight + ScaleGap + ScaleHeight)
            .semantics { contentDescription = description.format(Locale.US, reading.value?.value?.roundToInt() ?: 0) },
    ) {
        // Static layer: its own render node, so the moving bar never makes it redraw.
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawWithCache {
                    val geometry = SegmentGeometry(size.width, SegmentGap.toPx().roundToInt().coerceAtLeast(1).toFloat())
                    val barHeight = BarHeight.toPx()
                    val scale = (0..RPM_BAR_SEGMENTS step SEGMENTS_PER_SCALE_STEP).map { index ->
                        measurer.measure((index / SEGMENTS_PER_SCALE_STEP).toString(), KLineType.fieldLabel)
                    }
                    onDrawBehind {
                        for (i in 0 until RPM_BAR_SEGMENTS) {
                            val topLeft = Offset(geometry.left(i), 0f)
                            val segment = Size(geometry.width(i), barHeight)
                            drawRect(colors.surface, topLeft, segment)
                            val zone = rpmZone(i * SEGMENT_RPM)
                            if (zone != RpmZone.NORMAL) drawRect(colors.zoneColor(zone), topLeft, segment, UNLIT_ZONE_ALPHA)
                        }
                        val labelTop = barHeight + ScaleGap.toPx()
                        scale.forEachIndexed { step, label ->
                            // The last label (9 000) has no segment of its own: it ends where the bar ends.
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
                    val geometry = SegmentGeometry(size.width, SegmentGap.toPx().roundToInt().coerceAtLeast(1).toFloat())
                    val barHeight = BarHeight.toPx()
                    onDrawBehind {
                        val count = litSegmentCount(lit.value.toDouble())
                        val dimmed = stale.value
                        for (i in 0 until count) {
                            val color = if (dimmed) colors.inkFaint else colors.zoneColor(rpmZone(i * SEGMENT_RPM))
                            drawRect(color, Offset(geometry.left(i), 0f), Size(geometry.width(i), barHeight))
                        }
                    }
                },
        )
    }
}
