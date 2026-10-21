package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.delay
import nl.stijnvdpol.klinedash.AppGraph
import nl.stijnvdpol.klinedash.appGraph
import nl.stijnvdpol.klinedash.core.obd.ObdPoller
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private const val CLOCK_TICK_MS = 1_000L
private val ButtonHorizontalPadding = 24.dp
private val StatusDotSize = 16.dp

/** Creates an Activity-scoped ViewModel that gets the manual DI [AppGraph] (no Hilt). */
@Composable
inline fun <reified VM : ViewModel> graphViewModel(crossinline create: (AppGraph) -> VM): VM {
    val graph = LocalContext.current.appGraph
    return viewModel(factory = viewModelFactory { initializer { create(graph) } })
}

/** Flat tonal area: the design has no shadows, borders or gradients, spacing does the grouping. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: Dp = Spacing.panelPadding,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .background(KLineTheme.colors.panel, RoundedCornerShape(Spacing.panelRadius))
            .padding(padding),
        content = content,
    )
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = StatusDotSize) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** The one action a screen wants: ink on dark, so it stands out without an accent colour. */
@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = KLineTheme.colors
    ActionButton(
        label, onClick, modifier, enabled,
        ButtonDefaults.buttonColors(
            containerColor = colors.ink,
            contentColor = colors.background,
            disabledContainerColor = colors.panelRaised,
            disabledContentColor = colors.inkMuted,
        ),
    )
}

@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    horizontalPadding: Dp = ButtonHorizontalPadding,
) {
    val colors = KLineTheme.colors
    ActionButton(
        label, onClick, modifier, enabled,
        ButtonDefaults.buttonColors(
            containerColor = colors.panelRaised,
            contentColor = colors.ink,
            disabledContainerColor = colors.panelRaised,
            disabledContentColor = colors.inkMuted,
        ),
        horizontalPadding,
    )
}

@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    colors: ButtonColors,
    horizontalPadding: Dp = ButtonHorizontalPadding,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = Spacing.touchMin),
        enabled = enabled,
        shape = RoundedCornerShape(Spacing.controlRadius),
        colors = colors,
        contentPadding = PaddingValues(horizontal = horizontalPadding),
    ) { Text(label, style = KLineType.body.copy(fontWeight = FontWeight.Medium), maxLines = 2) }
}

/**
 * Tells the poller which signals this screen shows, only while the screen is resumed: leaving the
 * screen or the app releases the demand, so nothing is polled that nobody sees. A changed id set
 * (e.g. once the supported PIDs are known) replaces the demand.
 */
@Composable
fun DemandWhileVisible(poller: ObdPoller?, owner: String, signalIds: Set<String>) {
    LifecycleResumeEffect(poller, signalIds) {
        poller?.demand(owner, signalIds)
        onPauseOrDispose { poller?.release(owner) }
    }
}

/**
 * Wall-clock time that ticks once a second, handed around as State so that only the composables that
 * compare it with a reading's age recompose.
 */
@Composable
fun rememberNowMs(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(CLOCK_TICK_MS)
        value = System.currentTimeMillis()
    }
}

/** Width of [sample] in [style]: a value slot that never changes size while the number does. */
@Composable
fun rememberTextWidth(sample: String, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(sample, style, density) {
        with(density) { measurer.measure(sample, style).size.width.toDp() }
    }
}
