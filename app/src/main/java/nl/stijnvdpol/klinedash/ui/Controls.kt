package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private val ButtonHorizontalPadding = 8.dp
private val ToggleWidth = 64.dp
private val ToggleHeight = 32.dp
private val ToggleThumbInset = 5.dp

/** Section title on the Connection and Debug pages, aligned with the text of the rows below it. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = KLineType.title,
        color = KLineTheme.colors.ink,
        maxLines = 1,
        modifier = modifier.padding(
            horizontal = Spacing.fieldPaddingHorizontal,
            vertical = Spacing.fieldPaddingVertical,
        ),
    )
}

private enum class ButtonKind { FILLED, OUTLINED, TEXT }

/** The one action a screen wants: white fill, black text. */
@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    RaceButton(label, onClick, modifier, enabled, ButtonKind.FILLED)

/** A second action next to the primary one: 1 dp white outline. */
@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    RaceButton(label, onClick, modifier, enabled, ButtonKind.OUTLINED)

/** Text only, for quiet actions and for cells in a grid of buttons. */
@Composable
fun TextActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    RaceButton(label, onClick, modifier, enabled, ButtonKind.TEXT)

@Composable
private fun RaceButton(label: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean, kind: ButtonKind) {
    val colors = KLineTheme.colors
    val textColor = when {
        !enabled -> colors.inkFaint
        kind == ButtonKind.FILLED -> colors.background
        else -> colors.ink
    }
    val fill = when {
        kind != ButtonKind.FILLED -> Color.Transparent
        enabled -> colors.ink
        else -> colors.surface
    }
    val outline = if (kind == ButtonKind.OUTLINED) {
        Modifier.border(Spacing.ruleThickness, if (enabled) colors.ink else colors.rule)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .heightIn(min = Spacing.touchMin)
            .background(fill)
            .then(outline)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = ButtonHorizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = KLineType.fieldLabel, color = textColor, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/**
 * A choice in a list: the selected row has a 4 dp white bar on its left edge, no radio button.
 * Text starts at the field padding in both states, so selecting a row moves nothing. Every row
 * ends in a rule.
 */
@Composable
fun SelectableRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = KLineTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchMin)
            .drawBehind {
                val rule = Spacing.ruleThickness.toPx()
                drawRect(colors.rule, Offset(0f, size.height - rule), Size(size.width, rule))
                if (selected) drawRect(colors.ink, size = Size(Spacing.markerThickness.toPx(), size.height))
            }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Label on the left, the square toggle at the right edge, 72 dp high. No rule of its own: in a list
 * the caller adds one, in a toolbar cell the neighbouring rules already frame it.
 */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = Spacing.touchMin)
            .padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.fieldPaddingHorizontal),
    ) {
        Text(label, style = KLineType.body, color = KLineTheme.colors.ink, maxLines = 1, modifier = Modifier.weight(1f))
        Toggle(checked)
    }
}

/** Outlined slot with a square thumb: left is off (grey), right is on (white). */
@Composable
private fun Toggle(checked: Boolean) {
    val colors = KLineTheme.colors
    Row(
        modifier = Modifier
            .size(ToggleWidth, ToggleHeight)
            .border(Spacing.ruleThickness, if (checked) colors.ink else colors.inkMuted)
            .padding(ToggleThumbInset),
        horizontalArrangement = if (checked) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier
                .size(ToggleHeight - ToggleThumbInset * 2)
                .background(if (checked) colors.ink else colors.inkMuted),
        )
    }
}

/** Square input field on `surface`, 72 dp tall like every other target. */
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
        shape = RectangleShape,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.ink,
            unfocusedTextColor = colors.ink,
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            focusedBorderColor = colors.ink,
            unfocusedBorderColor = colors.rule,
            cursorColor = colors.ink,
        ),
    )
}
