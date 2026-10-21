package nl.stijnvdpol.klinedash.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.obd.PollMode
import nl.stijnvdpol.klinedash.service.formatNumber
import nl.stijnvdpol.klinedash.service.headline
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing
import nl.stijnvdpol.klinedash.ui.theme.accentColor

internal enum class Screen(@StringRes val labelRes: Int) {
    Dashboard(R.string.nav_dashboard),
    Sensors(R.string.nav_sensors),
    Diagnostics(R.string.nav_diagnostics),
    Connection(R.string.nav_connection),
    Debug(R.string.nav_debug),
}

private val NavBarHeight = 72.dp
private const val BUTTONS_WEIGHT = 6f
private const val STATUS_WEIGHT = 3f
private const val RATE_SAMPLE = "88.8/s"
private val StatusGap = 12.dp

/**
 * Bottom bar: five text buttons separated by rules, the active one marked by a 4 dp white bar on
 * its top edge, and the link status at the right end. Same on every page, so the status is always
 * in the same place.
 */
@Composable
internal fun NavBar(selected: Screen, onSelect: (Screen) -> Unit, modifier: Modifier = Modifier) {
    val ruleColor = KLineTheme.colors.rule
    Row(
        modifier
            .fillMaxWidth()
            .height(NavBarHeight)
            .drawBehind { drawRect(ruleColor, size = Size(size.width, Spacing.ruleThickness.toPx())) },
    ) {
        Row(Modifier.weight(BUTTONS_WEIGHT).fillMaxHeight()) {
            Screen.entries.forEach { screen ->
                NavButton(screen, screen == selected, { onSelect(screen) }, Modifier.weight(1f).fillMaxHeight())
                VerticalRule()
            }
        }
        LinkStatus(Modifier.weight(STATUS_WEIGHT).fillMaxHeight())
    }
}

@Composable
private fun NavButton(screen: Screen, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = KLineTheme.colors
    Box(
        modifier = modifier
            .drawBehind {
                if (isSelected) drawRect(colors.ink, size = Size(size.width, Spacing.markerThickness.toPx()))
            }
            .selectable(selected = isSelected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(screen.labelRes).uppercase(),
            style = KLineType.fieldLabel,
            color = if (isSelected) colors.ink else colors.inkMuted,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Lamp, protocol and the measured request rate; information, not a control. Connected shows the
 * protocol and rate, engine off says so, every other state names itself.
 */
@Composable
private fun LinkStatus(modifier: Modifier) {
    val vm = graphViewModel { LiveDataViewModel() }
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val colors = KLineTheme.colors
    val state = connection
    val rateWidth = rememberTextWidth(RATE_SAMPLE, KLineType.caption)
    Row(
        modifier.padding(horizontal = Spacing.fieldPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        StatusDot(state.accentColor(colors))
        Spacer(Modifier.width(StatusGap))
        if (state !is ConnectionState.Connected) {
            Text(state.headline(LocalContext.current.resources), style = KLineType.caption, color = colors.inkMuted, maxLines = 1)
        } else if (stats.mode == PollMode.ENGINE_OFF) {
            Text(stringResource(R.string.status_engine_off), style = KLineType.caption, color = colors.ink, maxLines = 1)
        } else {
            Text(state.info.protocolName, style = KLineType.caption, color = colors.inkMuted, maxLines = 1)
            Spacer(Modifier.width(StatusGap))
            val rate = if (stats.mode == PollMode.ACTIVE) formatNumber(stats.requestsPerSecond, 1) else MISSING_VALUE
            Text(
                stringResource(R.string.status_rate, rate),
                style = KLineType.caption,
                color = colors.ink,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(rateWidth),
            )
        }
    }
}
