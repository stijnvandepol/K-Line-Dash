package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
import nl.stijnvdpol.klinedash.ui.theme.Spacing
import kotlin.math.roundToInt

private const val CLOCK_TICK_MS = 1_000L
private val StatusDotSize = 12.dp

/** Cap height of Roboto as a fraction of the font size; digits and capitals are this tall. */
internal const val CAP_HEIGHT_EM = 0.711f

/** Creates an Activity-scoped ViewModel that gets the manual DI [AppGraph] (no Hilt). */
@Composable
inline fun <reified VM : ViewModel> graphViewModel(crossinline create: (AppGraph) -> VM): VM {
    val graph = LocalContext.current.appGraph
    return viewModel(factory = viewModelFactory { initializer { create(graph) } })
}

/** The 1 dp line that separates fields, rows and columns: the only structure the design has. */
@Composable
fun HorizontalRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(Spacing.ruleThickness).background(KLineTheme.colors.rule))
}

@Composable
fun VerticalRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight().width(Spacing.ruleThickness).background(KLineTheme.colors.rule))
}

/** The one round element: an indicator lamp, like the status LEDs of a race display. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = StatusDotSize) {
    Box(modifier.size(size).background(color, CircleShape))
}

/**
 * Shrinks a single line of capitals or digits to the height of its capitals: the box starts at the
 * cap line and ends at the baseline. Padding then measures from the glyph edges, so labels and
 * values sit exactly [Spacing.fieldPaddingVertical] from the rules, whatever the font line gap is.
 */
fun Modifier.capHeightBox(fontSize: TextUnit): Modifier = layout { measurable, constraints ->
    // The line is taller than its capitals (line gap above and below): measured without a height limit,
    // so a field only as tall as the capitals needs does not clip the text.
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val capHeight = (fontSize.toPx() * CAP_HEIGHT_EM).roundToInt()
    val baseline = placeable[FirstBaseline]
    layout(placeable.width, capHeight, mapOf(FirstBaseline to capHeight)) {
        placeable.place(0, capHeight - baseline)
    }
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
