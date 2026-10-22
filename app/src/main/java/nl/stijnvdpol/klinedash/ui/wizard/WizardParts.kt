package nl.stijnvdpol.klinedash.ui.wizard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.ui.HorizontalRule
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private val StepNumberWidth = 32.dp

/** The heading of a step, in `title`, 16 dp from the edge like every text. It wraps instead of cutting a long title off. */
@Composable
internal fun StepTitle(text: String) {
    Text(
        text,
        style = KLineType.title,
        color = KLineTheme.colors.ink,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
    )
}

/** A paragraph of a step in `body`; [color] is `inkMuted` unless it is the point of the screen. */
@Composable
internal fun StepText(text: String, modifier: Modifier = Modifier, color: Color = KLineTheme.colors.inkMuted) {
    Text(
        text,
        style = KLineType.body,
        color = color,
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid / 2),
    )
}

/** An instruction with its number in the margin, so a step reads as a short list of things to do. */
@Composable
internal fun NumberedStep(number: Int, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid / 2),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "$number",
            style = KLineType.body,
            color = KLineTheme.colors.ink,
            textAlign = TextAlign.Start,
            modifier = Modifier.width(StepNumberWidth),
        )
        Text(text, style = KLineType.body, color = KLineTheme.colors.ink, modifier = Modifier.weight(1f))
    }
}
