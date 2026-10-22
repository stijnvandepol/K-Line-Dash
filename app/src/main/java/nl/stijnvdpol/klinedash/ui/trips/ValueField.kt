package nl.stijnvdpol.klinedash.ui.trips

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import nl.stijnvdpol.klinedash.ui.FieldFrame
import nl.stijnvdpol.klinedash.ui.MISSING_VALUE
import nl.stijnvdpol.klinedash.ui.ValueLook
import nl.stijnvdpol.klinedash.ui.capHeightBox
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme

/**
 * A field of the display with a plain text value: [label] top-left, [unit] top-right, [value]
 * right-aligned at the bottom in the style of [look], shrunk (never wrapped) so that [slotSample]
 * fits. The value is dimmed while it is "--", like every missing value.
 */
@Composable
internal fun ValueField(
    label: String,
    unit: String?,
    value: String,
    look: ValueLook,
    slotSample: String,
    modifier: Modifier,
    color: Color = KLineTheme.colors.ink,
) {
    FieldFrame(label.uppercase(), unit?.uppercase(), look, slotSample, modifier) { style ->
        Text(
            value,
            style = style,
            color = if (value == MISSING_VALUE) KLineTheme.colors.inkFaint else color,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.capHeightBox(style.fontSize),
        )
    }
}
