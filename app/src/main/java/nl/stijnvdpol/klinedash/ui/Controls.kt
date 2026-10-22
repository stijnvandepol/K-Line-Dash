package nl.stijnvdpol.klinedash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.pointer.pointerInput
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
private val DialogWidth = 720.dp
private const val ScrimAlpha = 0.8f

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

private enum class ButtonKind { FILLED, OUTLINED, DANGER, DANGER_TEXT, TEXT }

/** The one action a screen wants: white fill, black text. */
@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    RaceButton(label, onClick, modifier, enabled, ButtonKind.FILLED)

/** A second action next to the primary one: 1 dp white outline. */
@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    RaceButton(label, onClick, modifier, enabled, ButtonKind.OUTLINED)

/** An action that erases something: 1 dp outline and text in the danger colour. */
@Composable
fun DangerButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    RaceButton(label, onClick, modifier, enabled, ButtonKind.DANGER)

/** Text only in the danger colour, for a delete cell in a list row. */
@Composable
fun DangerTextButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    RaceButton(label, onClick, modifier, enabled, ButtonKind.DANGER_TEXT)

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
        kind == ButtonKind.DANGER || kind == ButtonKind.DANGER_TEXT -> colors.danger
        else -> colors.ink
    }
    val fill = when {
        kind != ButtonKind.FILLED -> Color.Transparent
        enabled -> colors.ink
        else -> colors.surface
    }
    val outline = when {
        kind == ButtonKind.OUTLINED -> Modifier.border(Spacing.ruleThickness, if (enabled) colors.ink else colors.rule)
        kind == ButtonKind.DANGER -> Modifier.border(Spacing.ruleThickness, if (enabled) colors.danger else colors.rule)
        else -> Modifier
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
 * One row of equal 72 dp cells separated by rules, of which exactly one is chosen: the chosen cell
 * is filled, the others are text. Used where the choice switches what the rest of the page shows
 * (grid size, day or night theme).
 */
@Composable
fun ChoiceCells(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(Spacing.touchMin)) {
        labels.forEachIndexed { index, label ->
            if (index > 0) VerticalRule()
            val cell = Modifier.weight(1f).fillMaxHeight()
            if (index == selectedIndex) PrimaryButton(label, { onSelect(index) }, cell) else TextActionButton(label, { onSelect(index) }, cell)
        }
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
 * Label on the left (two lines at most, which still fit the 72 dp), the square toggle at the right edge. No rule of its own: in a list
 * the caller adds one, in a toolbar cell the neighbouring rules already frame it.
 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = KLineTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = Spacing.touchMin)
            .padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.fieldPaddingHorizontal),
    ) {
        Text(label, style = KLineType.body, color = if (enabled) colors.ink else colors.inkFaint, maxLines = 2, modifier = Modifier.weight(1f))
        Toggle(checked, enabled)
    }
}

/** Outlined slot with a square thumb: left is off (grey), right is on (white); all faint when disabled. */
@Composable
private fun Toggle(checked: Boolean, enabled: Boolean) {
    val colors = KLineTheme.colors
    val tone = when {
        !enabled -> colors.inkFaint
        checked -> colors.ink
        else -> colors.inkMuted
    }
    Row(
        modifier = Modifier
            .size(ToggleWidth, ToggleHeight)
            .border(Spacing.ruleThickness, tone)
            .padding(ToggleThumbInset),
        horizontalArrangement = if (checked) Arrangement.End else Arrangement.Start,
    ) {
        Box(Modifier.size(ToggleHeight - ToggleThumbInset * 2).background(tone))
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

/**
 * A modal in the language of the display: black, square, a 1 dp outline and a 4 dp bar along the
 * top edge in [accent] (ink; danger for a step that erases something). [content] sits between the
 * title and the [actions], which are 72 dp buttons in one row. Back dismisses; a tap on the dimmed
 * area does nothing, so a stray touch cannot answer a question.
 *
 * Drawn over the screen it is placed in, not in a window of its own: a second window makes some
 * head units show their system bars. Place it last in a full-size Box.
 */
@Composable
fun RaceDialog(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = KLineTheme.colors.ink,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = KLineTheme.colors
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = ScrimAlpha))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(modifier.width(DialogWidth).background(colors.background).border(Spacing.ruleThickness, colors.inkFaint)) {
            Box(Modifier.fillMaxWidth().height(Spacing.markerThickness).background(accent))
            Text(
                title,
                style = KLineType.title,
                color = colors.ink,
                modifier = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingHorizontal),
            )
            content()
            HorizontalRule()
            Row(
                Modifier.fillMaxWidth().padding(Spacing.fieldPaddingHorizontal),
                horizontalArrangement = Arrangement.spacedBy(Spacing.fieldPaddingHorizontal),
                content = actions,
            )
        }
    }
}
