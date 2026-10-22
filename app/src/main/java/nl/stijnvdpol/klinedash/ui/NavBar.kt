package nl.stijnvdpol.klinedash.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextDecoration
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
    Trips(R.string.nav_trips),
    Setup(R.string.nav_setup),
}

private val NavBarHeight = 72.dp
private const val BUTTONS_WEIGHT = 6f
private const val STATUS_WEIGHT = 3f
private const val RATE_SAMPLE = "88.8/s"
private val StatusGap = 12.dp

/**
 * Bottom bar: five text buttons separated by rules, the active one marked by a 4 dp bar in the accent colour on
 * its top edge, and the link status at the right end. Same on every page, so the status is always
 * in the same place.
 */
@Composable
internal fun NavBar(selected: Screen, onSelect: (Screen) -> Unit, onOpenGps: () -> Unit, modifier: Modifier = Modifier) {
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
        LinkStatus(onOpenGps, Modifier.weight(STATUS_WEIGHT).fillMaxHeight())
    }
}

@Composable
private fun NavButton(screen: Screen, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = KLineTheme.colors
    Box(
        modifier = modifier
            .drawBehind {
                if (isSelected) drawRect(colors.accent, size = Size(size.width, Spacing.markerThickness.toPx()))
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
 * The GPS mark, then lamp, protocol and the measured request rate; information, not a control (the
 * GPS mark is the one exception: it opens SETUP > GPS & Tracks). Connected shows the protocol and
 * rate, engine off says so, every other state names itself.
 */
@Composable
private fun LinkStatus(onOpenGps: () -> Unit, modifier: Modifier) {
    val vm = graphViewModel { LiveDataViewModel.forGraph(it) }
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val colors = KLineTheme.colors
    val state = connection
    val rateWidth = rememberTextWidth(RATE_SAMPLE, KLineType.caption)
    Row(
        modifier.padding(end = Spacing.fieldPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        GpsMarkButton(onOpenGps, Modifier.fillMaxHeight())
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

/**
 * "GPS" in `fieldLabel`: `ok` with the satellite count while there is a fix, `inkMuted` while
 * searching, `inkFaint` and struck through when location is off or not allowed. The whole height of
 * the bar is the target, the mark itself stays small.
 */
@Composable
private fun GpsMarkButton(onClick: () -> Unit, modifier: Modifier) {
    val vm = graphViewModel { GpsMarkViewModel.forGraph(it) }
    val mark by vm.mark.collectAsStateWithLifecycle()
    val colors = KLineTheme.colors
    val color = when (mark.kind) {
        GpsMarkKind.FIX -> colors.ok
        GpsMarkKind.SEARCHING -> colors.inkMuted
        GpsMarkKind.OFF -> colors.inkFaint
    }
    Row(
        modifier.clickable(role = Role.Button, onClick = onClick).padding(horizontal = Spacing.fieldPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.grid),
    ) {
        Text(
            stringResource(R.string.gps_mark),
            style = KLineType.fieldLabel,
            color = color,
            textDecoration = if (mark.kind == GpsMarkKind.OFF) TextDecoration.LineThrough else null,
            maxLines = 1,
        )
        mark.satellites?.let { Text(it.toString(), style = KLineType.caption, color = colors.inkMuted, maxLines = 1) }
    }
}
