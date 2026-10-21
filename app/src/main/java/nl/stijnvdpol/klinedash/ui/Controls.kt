package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing


@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = KLineType.title, color = KLineTheme.colors.ink, maxLines = 1, modifier = modifier)
}

/**
 * Label on the left, radio button at the right edge. [horizontalInset] is the space between the
 * edge of the row (the raised pill) and its text; give titles and hints the same inset to line up. Radios and switches end on the same line, so
 * every control of a settings panel sits in one column. The chosen row is a raised panel.
 */
@Composable
fun RadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalInset: Dp = Spacing.panelPadding,
) {
    val colors = KLineTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Spacing.controlRadius))
            .background(if (selected) colors.panelRaised else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = Spacing.touchMin)
            .padding(horizontal = horizontalInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = KLineType.body, color = colors.ink, modifier = Modifier.weight(1f))
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = colors.ink, unselectedColor = colors.inkMuted),
        )
    }
}

/** [supportingText] sits under the label, muted; the switch is at the right edge like a [RadioRow]'s radio. */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    horizontalInset: Dp = Spacing.panelPadding,
) {
    val colors = KLineTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Spacing.controlRadius))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = Spacing.touchMin)
            .padding(horizontal = horizontalInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = KLineType.body, color = colors.ink)
            if (supportingText != null) Text(supportingText, style = KLineType.label, color = colors.inkMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.background,
                checkedTrackColor = colors.ink,
                checkedBorderColor = colors.ink,
                uncheckedThumbColor = colors.inkMuted,
                uncheckedTrackColor = colors.panelRaised,
                uncheckedBorderColor = colors.line,
            ),
        )
    }
}

/** Input field on a raised surface, 72 dp tall like every other target. */
@Composable
fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    textStyle: TextStyle = KLineType.body,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = KLineTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = Spacing.touchMin),
        textStyle = textStyle,
        placeholder = placeholder?.let { { Text(it, style = textStyle, color = colors.inkMuted) } },
        singleLine = true,
        shape = RoundedCornerShape(Spacing.controlRadius),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.ink,
            unfocusedTextColor = colors.ink,
            focusedContainerColor = colors.panelRaised,
            unfocusedContainerColor = colors.panelRaised,
            focusedBorderColor = colors.ink,
            unfocusedBorderColor = colors.line,
            cursorColor = colors.ink,
        ),
    )
}
