package nl.stijnvdpol.klinedash.ui.cluster

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.ui.CAP_HEIGHT_EM
import nl.stijnvdpol.klinedash.ui.LocalUnitSystem
import nl.stijnvdpol.klinedash.ui.RPM_ID
import nl.stijnvdpol.klinedash.ui.SPEED_ID
import nl.stijnvdpol.klinedash.ui.ValueText
import nl.stijnvdpol.klinedash.ui.fieldLabel
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.LocalClusterLook
import nl.stijnvdpol.klinedash.ui.theme.numeralStyle
import kotlin.math.roundToInt

private const val DIMMED_ALPHA = 0.4f
private const val TACH_MINOR_STEP_RPM = 500
private const val TACH_MAJOR_STEP_RPM = 1_000
private const val SPEEDO_MINOR_STEP_KMH = 10
private const val SPEEDO_MAJOR_STEP_KMH = 20
private const val SPEEDO_LABEL_STEP_KMH = 40

/** The tachometer of the twin and centre styles: 0 to 9 with "x1000" on it, the caution and redline bands. */
@Composable
internal fun rememberTachSpec(): DialSpec {
    val scale = stringResource(R.string.cluster_tach_scale)
    val caption = fieldLabel(checkNotNull(Signals.byId(RPM_ID)))
    return remember(scale, caption) {
        DialSpec(DialTach, TACH_MINOR_STEP_RPM, TACH_MAJOR_STEP_RPM, TACH_MAJOR_STEP_RPM, TACH_MAJOR_STEP_RPM, hasZones = true, topCaption = scale, caption = caption)
    }
}

/** The speedometer of the twin style: 0 to 260 km/h or 0 to 160 mph, a numeral every 40. */
@Composable
internal fun rememberSpeedoSpec(): DialSpec {
    val caption = fieldLabel(checkNotNull(Signals.byId(SPEED_ID)))
    val units = LocalUnitSystem.current
    return remember(caption, units) {
        DialSpec(dialSpeedo(units), SPEEDO_MINOR_STEP_KMH, SPEEDO_MAJOR_STEP_KMH, SPEEDO_LABEL_STEP_KMH, 1, hasZones = false, topCaption = null, caption = caption)
    }
}

/**
 * A round dial with a needle and a digital readout of the same signal below the hub. The static
 * parts are drawn once into their own layer ([DialPainter]); the needle layer reads the glided
 * value in the draw phase, so a moving needle redraws one path and recomposes nothing. The dial is
 * as big as its square [modifier] makes it; [readoutSample] sets the width of the readout's slot.
 */
@Composable
internal fun NeedleDial(spec: DialSpec, feed: GaugeFeed, readoutSample: String, modifier: Modifier = Modifier) {
    val colors = KLineTheme.colors
    val numerals = LocalClusterLook.current.numerals
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val diameterPx = with(LocalDensity.current) { minOf(maxWidth, maxHeight).toPx() }
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawWithCache {
                    val painter = DialPainter(size.minDimension, this, spec, colors, numerals, measurer)
                    onDrawBehind { painter.draw(this) }
                },
        )
        DialReadout(feed, readoutSample, diameterPx, colors.faceInk, Modifier.align(Alignment.TopCenter))
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val painter = NeedlePainter(size.minDimension, spec.scale, colors)
                    onDrawBehind { painter.draw(this, feed.drawnValue()) }
                },
        )
    }
}

/** The digital value of a dial, its baseline at [READOUT_BASELINE] below the centre; dim when stale. */
@Composable
private fun DialReadout(feed: GaugeFeed, sample: String, diameterPx: Float, ink: Color, modifier: Modifier) {
    val density = LocalDensity.current
    val numerals = LocalClusterLook.current.numerals
    val unit = diameterPx / DIAL_REF
    val style = remember(numerals, unit, density) { numeralStyle(numerals, with(density) { (READOUT_SIZE * unit).toSp() }) }
    val capHeight = (READOUT_SIZE * unit * CAP_HEIGHT_EM).roundToInt()
    val top = (diameterPx / 2f + READOUT_BASELINE * unit).roundToInt() - capHeight
    ValueText(
        feed.signal, feed.reading, feed.stale, feed.tone, style,
        modifier.offset { IntOffset(0, top) }, sample,
        ink = ink, dimmed = ink.copy(alpha = DIMMED_ALPHA), align = TextAlign.Center,
    )
}
