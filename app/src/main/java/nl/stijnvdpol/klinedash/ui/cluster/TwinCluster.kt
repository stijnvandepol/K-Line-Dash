package nl.stijnvdpol.klinedash.ui.cluster

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.ui.COOLANT_ID
import nl.stijnvdpol.klinedash.ui.LiveState
import nl.stijnvdpol.klinedash.ui.RPM_ID
import nl.stijnvdpol.klinedash.ui.SPEED_ID

private val EdgeMargin = 16.dp
private const val RPM_READOUT_SAMPLE = "8888"
private const val SPEED_READOUT_SAMPLE = "888"

/**
 * Twin dials: tachometer left, speedometer right, both as big as the area allows, and between them
 * the slim coolant dial. All three are centred as one group, so on a wide area the dials stay
 * together instead of drifting to the edges.
 */
@Composable
internal fun TwinCluster(live: LiveState, modifier: Modifier = Modifier) {
    val rpm = rememberGaugeFeed(live, RPM_ID, glides = true)
    val speed = rememberGaugeFeed(live, SPEED_ID, glides = true)
    val coolant = rememberGaugeFeed(live, COOLANT_ID, glides = false)
    val tach = rememberTachSpec()
    val speedo = rememberSpeedoSpec()
    BoxWithConstraints(modifier.clipToBounds()) {
        val density = LocalDensity.current
        val dialPx = twinDialSize(with(density) { maxWidth.toPx() }, with(density) { maxHeight.toPx() }, with(density) { EdgeMargin.toPx() })
        val dial = with(density) { dialPx.toDp() }
        Row(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterVertically) {
            NeedleDial(tach, rpm, RPM_READOUT_SAMPLE, Modifier.size(dial))
            CoolantDial(coolant, live.toneContext.value.limits, Modifier.width(with(density) { twinCoolantColumn(dialPx).toDp() }).height(dial))
            NeedleDial(speedo, speed, SPEED_READOUT_SAMPLE, Modifier.size(dial))
        }
    }
}
