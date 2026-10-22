package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.data.DashboardLayout
import nl.stijnvdpol.klinedash.data.GridPage
import nl.stijnvdpol.klinedash.data.GridSize
import nl.stijnvdpol.klinedash.data.MAX_PAGES
import nl.stijnvdpol.klinedash.data.MainPage
import nl.stijnvdpol.klinedash.data.Page
import nl.stijnvdpol.klinedash.data.PageTemplate
import nl.stijnvdpol.klinedash.data.TrackPage
import nl.stijnvdpol.klinedash.data.GridCell
import nl.stijnvdpol.klinedash.data.signalAt
import nl.stijnvdpol.klinedash.ui.pages.PagePreview
import nl.stijnvdpol.klinedash.ui.pages.SignalPickerDialog
import nl.stijnvdpol.klinedash.ui.pages.pickableSignals
import nl.stijnvdpol.klinedash.ui.pages.rememberPageTitles
import nl.stijnvdpol.klinedash.ui.pages.templateLabelRes
import nl.stijnvdpol.klinedash.service.formatNumber
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private val PageListWidth = 320.dp
private val PageListMinWidth = 220.dp
private const val PAGE_LIST_SHARE = 0.32f

/** The template names of the add dialog share one column, so their descriptions start at one x. */
private val TemplateLabelWidth = 120.dp

/** What the editor asks the user about right now, if anything. */
private sealed interface EditorDialog {
    data object AddPage : EditorDialog
    data object DeletePage : EditorDialog
    data class PickSignal(val slot: Int) : EditorDialog
}

/**
 * SETUP > Dashboard: the page list on the left (add, delete, move), the selected page as a scaled
 * preview on the right. Tap a cell of the preview to choose its signal. Every change is stored at once.
 */
@Composable
internal fun DashboardSetupScreen() {
    val vm = graphViewModel { DashboardEditorViewModel.forGraph(it) }
    val loaded by vm.layout.collectAsStateWithLifecycle()
    val selected by vm.selectedPage.collectAsStateWithLifecycle()
    val supportedPids by vm.supportedPids.collectAsStateWithLifecycle()
    val layout = loaded ?: return
    val index = selected.coerceIn(layout.pages.indices)
    val titles = rememberPageTitles(layout.pages)
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val listWidth = sideColumnWidth(maxWidth, PAGE_LIST_SHARE, PageListMinWidth, PageListWidth)
        Row(Modifier.fillMaxSize()) {
            PageListColumn(layout, titles, index, vm, { dialog = it }, Modifier.width(listWidth).fillMaxHeight())
            VerticalRule()
            PageEditorColumn(layout.pages[index], titles[index], supportedPids, vm, { dialog = EditorDialog.PickSignal(it) }, Modifier.weight(1f).fillMaxHeight())
        }
        when (val open = dialog) {
            null -> Unit
            EditorDialog.AddPage -> AddPageDialog(onAdd = { vm.addPage(it); dialog = null }, onDismiss = { dialog = null })
            EditorDialog.DeletePage -> ConfirmDeleteDialog(
                stringResource(R.string.dash_delete_title), stringResource(R.string.dash_delete_body, titles[index]), stringResource(R.string.dash_delete_page),
                onCancel = { dialog = null },
                onDelete = { vm.deleteSelectedPage(); dialog = null },
            )
            is EditorDialog.PickSignal -> PickSignalDialog(layout.pages[index], open.slot, supportedPids, vm) { dialog = null }
        }
    }
}

@Composable
private fun PageListColumn(
    layout: DashboardLayout,
    titles: List<String>,
    selectedIndex: Int,
    vm: DashboardEditorViewModel,
    onDialog: (EditorDialog) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        SectionTitle(stringResource(R.string.dash_pages_title), Modifier.height(Spacing.sectionHeaderHeight))
        HorizontalRule()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            titles.forEachIndexed { position, title ->
                val isSelected = position == selectedIndex
                SelectableRow(isSelected, { vm.selectPage(position) }) {
                    Text(
                        stringResource(R.string.dash_page_row, (position + 1).toString(), title),
                        style = KLineType.body,
                        color = if (isSelected) KLineTheme.colors.ink else KLineTheme.colors.inkMuted,
                    )
                }
            }
        }
        HorizontalRule()
        ActionRow(
            stringResource(R.string.dash_move_left), { vm.moveSelectedPage(-1) }, selectedIndex > 0,
            stringResource(R.string.dash_move_right), { vm.moveSelectedPage(1) }, selectedIndex < layout.pages.lastIndex,
        )
        HorizontalRule()
        ActionRow(
            stringResource(R.string.dash_add_page), { onDialog(EditorDialog.AddPage) }, layout.pages.size < MAX_PAGES,
            stringResource(R.string.dash_delete_page), { onDialog(EditorDialog.DeletePage) }, layout.pages.size > 1,
        )
    }
}

/** Two 72 dp text cells side by side, separated by a rule. */
@Composable
private fun ActionRow(
    firstLabel: String, onFirst: () -> Unit, isFirstEnabled: Boolean,
    secondLabel: String, onSecond: () -> Unit, isSecondEnabled: Boolean,
) {
    Row(Modifier.fillMaxWidth().height(Spacing.touchMin)) {
        TextActionButton(firstLabel, onFirst, Modifier.weight(1f).fillMaxHeight(), isFirstEnabled)
        VerticalRule()
        TextActionButton(secondLabel, onSecond, Modifier.weight(1f).fillMaxHeight(), isSecondEnabled)
    }
}

@Composable
private fun PageEditorColumn(
    page: Page,
    title: String,
    supportedPids: Set<Int>?,
    vm: DashboardEditorViewModel,
    onEditSlot: (Int) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        SectionTitle(title, Modifier.height(Spacing.sectionHeaderHeight))
        HorizontalRule()
        // The page's one setting, if it has one, directly under the title.
        when (page) {
            is MainPage -> Unit
            is GridPage -> {
                GridSizeChoice(page.size, vm::setGridSize)
                HorizontalRule()
            }
            is TrackPage -> {
                StepperRow(
                    stringResource(R.string.dash_shift_point), formatNumber(page.shiftRpm.toDouble(), 0), stringResource(R.string.unit_rpm),
                    StepWords.LOWER_RAISE, vm::stepShiftRpm,
                    canStep = { direction -> canStepShiftRpm(page, direction) },
                )
                HorizontalRule()
            }
        }
        Text(
            stringResource(hintRes(page.template)),
            style = KLineType.caption,
            color = KLineTheme.colors.inkMuted,
            modifier = Modifier.padding(Spacing.fieldPaddingHorizontal),
        )
        PagePreview(page, supportedPids, onEditSlot, Modifier.weight(1f).padding(start = Spacing.fieldPaddingHorizontal, end = Spacing.fieldPaddingHorizontal, bottom = Spacing.fieldPaddingHorizontal))
    }
}

private fun hintRes(template: PageTemplate): Int = when (template) {
    PageTemplate.MAIN -> R.string.dash_hint_main
    PageTemplate.GRID -> R.string.dash_hint_grid
    PageTemplate.TRACK -> R.string.dash_hint_track
}

@Composable
private fun GridSizeChoice(current: GridSize, onSelect: (GridSize) -> Unit) {
    val sizes = GridSize.entries
    ChoiceCells(
        labels = sizes.map { stringResource(R.string.dash_grid_size, it.columns.toString(), it.rows.toString()) },
        selectedIndex = sizes.indexOf(current),
        onSelect = { onSelect(sizes[it]) },
    )
}

@Composable
private fun AddPageDialog(onAdd: (PageTemplate) -> Unit, onDismiss: () -> Unit) {
    val focus = rememberInitialFocus()
    RaceDialog(
        title = stringResource(R.string.dash_add_title),
        onDismiss = onDismiss,
        actions = { SecondaryButton(stringResource(R.string.btn_cancel), onDismiss, Modifier.weight(1f).focusRequester(focus)) },
    ) {
        HorizontalRule()
        PageTemplate.entries.forEach { template ->
            SelectableRow(selected = false, onClick = { onAdd(template) }) {
                Text(stringResource(templateLabelRes(template)), style = KLineType.body, color = KLineTheme.colors.ink, modifier = Modifier.width(TemplateLabelWidth))
                Text(stringResource(templateHintRes(template)), style = KLineType.caption, color = KLineTheme.colors.inkMuted)
            }
        }
    }
}

private fun templateHintRes(template: PageTemplate): Int = when (template) {
    PageTemplate.MAIN -> R.string.dash_template_main
    PageTemplate.GRID -> R.string.dash_template_grid
    PageTemplate.TRACK -> R.string.dash_template_track
}

/** The picker for [slot] of [page]: a Main slot or a grid cell (which also gets the widget choice). */
@Composable
private fun PickSignalDialog(page: Page, slot: Int, supportedPids: Set<Int>?, vm: DashboardEditorViewModel, onClose: () -> Unit) {
    val gridCell = (page as? GridPage)?.cells?.getOrNull(slot)
    val currentId = gridCell?.signalId ?: (page as? MainPage)?.signalAt(slot) ?: return
    SignalPickerDialog(
        currentSignalId = currentId,
        signals = remember(supportedPids) { pickableSignals(supportedPids) },
        isCarKnown = supportedPids != null,
        widget = gridCell?.widget,
        onWidget = { widget -> vm.setGridCell(slot, GridCell(currentId, widget)) },
        onPick = { id ->
            if (gridCell != null) vm.setGridCell(slot, gridCell.copy(signalId = id)) else vm.setMainSignal(slot, id)
            onClose()
        },
        onDismiss = onClose,
    )
}
