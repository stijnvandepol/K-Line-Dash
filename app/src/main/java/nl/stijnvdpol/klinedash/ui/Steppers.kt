package nl.stijnvdpol.klinedash.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

internal val StepButtonWidth = 72.dp
internal val StepValueWidth = 104.dp

/** Holding a step button repeats it, so a limit 20 degrees away is not 20 taps. */
private const val REPEAT_DELAY_MS = 450L
private const val REPEAT_INTERVAL_MS = 90L

/** The plus and minus are the touch targets of the page, so they are larger than a table value. */
private val StepGlyphSize = 40.sp

internal const val MINUS = "−"
internal const val PLUS = "+"

/** What the two step cells add to the row's label for accessibility services ("Day starts, earlier"). */
internal enum class StepWords(@StringRes val lower: Int, @StringRes val raise: Int) {
    LOWER_RAISE(R.string.step_lower, R.string.step_raise),
    EARLIER_LATER(R.string.display_earlier, R.string.display_later),
}

/**
 * A 72 dp row: [label] on the left, then − value + as 72 dp cells separated by rules, the value
 * (with its [unit], if any) centred in a [StepValueWidth] cell. [onStep] gets -1 or 1; [canStep]
 * says whether a direction is still possible, so the cell at the end of the range is faint.
 */
@Composable
internal fun StepperRow(
    label: String,
    value: String,
    unit: String?,
    words: StepWords,
    onStep: (direction: Int) -> Unit,
    canStep: (direction: Int) -> Boolean = { true },
) {
    val colors = KLineTheme.colors
    Row(Modifier.fillMaxWidth().height(Spacing.touchMin), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label, style = KLineType.body, color = colors.ink, maxLines = 1,
            modifier = Modifier.weight(1f).padding(horizontal = Spacing.fieldPaddingHorizontal),
        )
        VerticalRule()
        StepButton(MINUS, "$label, ${stringResource(words.lower)}", canStep(-1)) { onStep(-1) }
        VerticalRule()
        Row(
            Modifier.width(StepValueWidth),
            horizontalArrangement = Arrangement.spacedBy(Spacing.grid / 2, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(value, style = KLineType.rowValue, color = colors.ink, maxLines = 1)
            if (unit != null) Text(unit, style = KLineType.caption, color = colors.inkMuted, maxLines = 1)
        }
        VerticalRule()
        StepButton(PLUS, "$label, ${stringResource(words.raise)}", canStep(1)) { onStep(1) }
    }
}

/**
 * A 72 x 72 dp cell that steps once on press and keeps stepping while it is held. Lit while pressed,
 * faint at the end of the range. A custom gesture, so the click action for accessibility is set by hand.
 */
@Composable
internal fun StepButton(glyph: String, description: String, enabled: Boolean, onStep: () -> Unit) {
    val colors = KLineTheme.colors
    val currentStep by rememberUpdatedState(onStep)
    var isPressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .width(StepButtonWidth)
            .fillMaxHeight()
            .background(if (isPressed) colors.surface else colors.background)
            .semantics {
                role = Role.Button
                contentDescription = description
                if (!enabled) disabled()
                onClick {
                    currentStep()
                    true
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    isPressed = true
                    currentStep()
                    coroutineScope {
                        val repeater = launch {
                            delay(REPEAT_DELAY_MS)
                            while (true) {
                                currentStep()
                                delay(REPEAT_INTERVAL_MS)
                            }
                        }
                        tryAwaitRelease()
                        repeater.cancel()
                    }
                    isPressed = false
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = KLineType.rowValue.copy(fontSize = StepGlyphSize), color = if (enabled) colors.ink else colors.inkFaint)
    }
}
