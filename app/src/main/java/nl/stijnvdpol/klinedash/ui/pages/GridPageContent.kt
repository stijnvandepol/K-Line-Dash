package nl.stijnvdpol.klinedash.ui.pages

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.data.GridCell
import nl.stijnvdpol.klinedash.data.GridPage
import nl.stijnvdpol.klinedash.data.WidgetType
import nl.stijnvdpol.klinedash.ui.CAP_HEIGHT_EM
import nl.stijnvdpol.klinedash.ui.DataField
import nl.stijnvdpol.klinedash.ui.HorizontalRule
import nl.stijnvdpol.klinedash.ui.LiveState
import nl.stijnvdpol.klinedash.ui.ValueLook
import nl.stijnvdpol.klinedash.ui.VerticalRule
import nl.stijnvdpol.klinedash.ui.labelAndGapHeight
import nl.stijnvdpol.klinedash.ui.LocalUnitSystem
import nl.stijnvdpol.klinedash.ui.slotSample
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing
import nl.stijnvdpol.klinedash.ui.valueScale

/** A number fills its cell like the primary fields do: as big as the cell allows, within this factor. */
private const val NUMBER_MAX_GROWTH = 3f

/**
 * A grid of equal cells separated by rules; each cell is a number or a graph of the last 60 s.
 * [history] and [graphRevision] feed the graphs.
 */
@Composable
internal fun GridPageContent(page: GridPage, live: LiveState, history: GraphHistory, graphRevision: State<Int>) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val numberLook = rememberNumberLook(page.cells, maxWidth / page.size.columns, maxHeight / page.size.rows)
        Column(Modifier.fillMaxSize()) {
            for (row in 0 until page.size.rows) {
                if (row > 0) HorizontalRule()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (column in 0 until page.size.columns) {
                        if (column > 0) VerticalRule()
                        val cell = page.cells[row * page.size.columns + column]
                        GridCellContent(cell, numberLook, live, history, graphRevision, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun GridCellContent(
    cell: GridCell,
    numberLook: ValueLook,
    live: LiveState,
    history: GraphHistory,
    graphRevision: State<Int>,
    modifier: Modifier,
) {
    when (cell.widget) {
        WidgetType.NUMBER -> DataField(cell.signalId, numberLook, live, modifier)
        WidgetType.GRAPH -> GraphCell(cell.signalId, live, history, graphRevision, modifier)
    }
}

/**
 * One size for every number of the grid, so "0.0" and "13.9" side by side read at the same height:
 * the largest size at which the widest value (the signal with the widest slot) still fits a cell.
 */
@Composable
private fun rememberNumberLook(cells: List<GridCell>, cellWidth: Dp, cellHeight: Dp): ValueLook {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val units = LocalUnitSystem.current
    return remember(cells, cellWidth, cellHeight, density, units) {
        val style = KLineType.primaryValue
        val roomWidth = cellWidth - Spacing.fieldPaddingHorizontal * 2
        val roomHeight = cellHeight - Spacing.fieldPaddingVertical * 2 - density.labelAndGapHeight()
        val digitHeight = with(density) { style.fontSize.toDp() } * CAP_HEIGHT_EM
        val scale = cells.filter { it.widget == WidgetType.NUMBER }
            .mapNotNull { Signals.byId(it.signalId) }
            .minOfOrNull { signal ->
                val slotWidth = with(density) { measurer.measure(slotSample(signal, units), style).size.width.toDp() }
                valueScale(slotWidth.value, roomWidth.value, digitHeight.value, roomHeight.value, NUMBER_MAX_GROWTH)
            } ?: NUMBER_MAX_GROWTH
        ValueLook(style, scale)
    }
}
