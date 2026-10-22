package nl.stijnvdpol.klinedash.ui.cluster

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.ui.BATTERY_ID
import nl.stijnvdpol.klinedash.ui.COOLANT_ID
import nl.stijnvdpol.klinedash.ui.DataField
import nl.stijnvdpol.klinedash.ui.HorizontalRule
import nl.stijnvdpol.klinedash.ui.LiveState
import nl.stijnvdpol.klinedash.ui.RPM_ID
import nl.stijnvdpol.klinedash.ui.SPEED_ID
import nl.stijnvdpol.klinedash.ui.ValueLook
import nl.stijnvdpol.klinedash.ui.VerticalRule
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.LocalClusterLook
import nl.stijnvdpol.klinedash.ui.theme.numeralStyle

private val DialGutter = 16.dp
private const val SIDE_MAX_GROWTH = 1.6f
private const val RPM_READOUT_SAMPLE = "8888"

/**
 * Centre tach: one large dial in the middle with the rpm in it, the speed large on its left, the
 * coolant and the battery stacked on its right. The side fields are the ordinary fields of the
 * dashboard (label, unit, value at the bottom), so they read like the rest of the page.
 */
@Composable
internal fun CenterCluster(live: LiveState, modifier: Modifier = Modifier) {
    val rpm = rememberGaugeFeed(live, RPM_ID, glides = true)
    val tach = rememberTachSpec()
    val numerals = LocalClusterLook.current.numerals
    val look = remember(numerals) { ValueLook(numeralStyle(numerals, KLineType.primaryValue.fontSize), SIDE_MAX_GROWTH) }
    BoxWithConstraints(modifier.clipToBounds()) {
        val density = LocalDensity.current
        val dial = with(density) { centreDialSize(maxWidth.toPx(), maxHeight.toPx()).toDp() }
        Row(Modifier.fillMaxSize()) {
            DataField(SPEED_ID, look, live, Modifier.weight(1f).fillMaxHeight())
            VerticalRule()
            Box(Modifier.width(dial + DialGutter * 2).fillMaxHeight(), contentAlignment = Alignment.Center) {
                NeedleDial(tach, rpm, RPM_READOUT_SAMPLE, Modifier.size(dial))
            }
            VerticalRule()
            Column(Modifier.weight(1f).fillMaxHeight()) {
                DataField(COOLANT_ID, look, live, Modifier.weight(1f).fillMaxWidth())
                HorizontalRule()
                DataField(BATTERY_ID, look, live, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}
