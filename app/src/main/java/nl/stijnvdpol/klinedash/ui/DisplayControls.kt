package nl.stijnvdpol.klinedash.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.ui.theme.AccentSwatches
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing
import nl.stijnvdpol.klinedash.ui.theme.formatMinuteOfDay

private val SwatchCell = 72.dp
private val SwatchInset = 12.dp

/** The colour square starts [SwatchInset] into its cell; this puts it on the 16 dp text edge. */
private val SwatchGridStart = Spacing.fieldPaddingHorizontal - SwatchInset
private val SelectionFrame = 4.dp
private const val OPAQUE = 0xFF000000.toInt()
private const val MAX_SWATCHES_PER_ROW = 8

/** Label, then [-] time [+] in the 72 dp cells of the Warnings steppers; [onStep] gets -1 or 1. */
@Composable
internal fun ScheduleRow(@StringRes labelRes: Int, minuteOfDay: Int, onStep: (direction: Int) -> Unit) {
    val label = stringResource(labelRes)
    Row(Modifier.fillMaxWidth().height(Spacing.touchMin), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = KLineType.body, color = KLineTheme.colors.ink, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = Spacing.fieldPaddingHorizontal))
        VerticalRule()
        StepButton(MINUS, "$label, ${stringResource(R.string.display_earlier)}", true) { onStep(-1) }
        VerticalRule()
        Box(Modifier.width(StepValueWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(formatMinuteOfDay(minuteOfDay), style = KLineType.rowValue, color = KLineTheme.colors.ink, textAlign = TextAlign.Center, maxLines = 1)
        }
        VerticalRule()
        StepButton(PLUS, "$label, ${stringResource(R.string.display_later)}", true) { onStep(1) }
    }
    HorizontalRule()
}

/** The 8 swatches as 72 dp cells (as many per row as fit), then "Theme default"; [chosen] is null for the default. */
@Composable
internal fun AccentPicker(chosen: Int?, onChoose: (Int?) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val perRow = ((maxWidth - SwatchGridStart) / SwatchCell).toInt().coerceIn(1, MAX_SWATCHES_PER_ROW)
        Column(Modifier.padding(start = SwatchGridStart)) {
            AccentSwatches.chunked(perRow).forEach { rowSwatches ->
                Row(horizontalArrangement = Arrangement.Start) {
                    rowSwatches.forEach { rgb -> AccentSwatch(rgb, selected = rgb == chosen, onClick = { onChoose(rgb) }) }
                }
            }
        }
    }
    HorizontalRule()
    SelectableRow(selected = chosen == null, onClick = { onChoose(null) }) {
        Text(
            stringResource(R.string.display_accent_default),
            style = KLineType.body,
            color = if (chosen == null) KLineTheme.colors.ink else KLineTheme.colors.inkMuted,
        )
    }
}

/** A colour square in a 72 dp cell; the chosen one gets a 4 dp ink frame at the edge of the cell. */
@Composable
private fun AccentSwatch(rgb: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = KLineTheme.colors
    val description = stringResource(R.string.display_swatch_description, "#%06X".format(rgb))
    Box(
        Modifier
            .size(SwatchCell)
            .semantics { contentDescription = description }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .drawBehind {
                val inset = SwatchInset.toPx()
                val swatchTopLeft = Offset(inset, inset)
                val swatchSize = Size(size.width - inset * 2, size.height - inset * 2)
                drawRect(Color(OPAQUE or rgb), swatchTopLeft, swatchSize)
                drawRect(colors.inkFaint, swatchTopLeft, swatchSize, style = Stroke(Spacing.ruleThickness.toPx()))
                if (selected) {
                    val frame = SelectionFrame.toPx()
                    drawRect(colors.ink, Offset(frame / 2, frame / 2), Size(size.width - frame, size.height - frame), style = Stroke(frame))
                }
            },
    )
}
