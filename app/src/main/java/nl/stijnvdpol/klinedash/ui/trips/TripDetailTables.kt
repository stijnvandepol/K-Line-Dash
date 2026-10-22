package nl.stijnvdpol.klinedash.ui.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.ui.FieldCaption
import nl.stijnvdpol.klinedash.ui.HorizontalRule
import nl.stijnvdpol.klinedash.ui.MISSING_VALUE
import nl.stijnvdpol.klinedash.ui.SelectableRow
import nl.stijnvdpol.klinedash.ui.VerticalRule
import nl.stijnvdpol.klinedash.ui.capHeightBox
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private val StatCellHeight = 64.dp
private val LapHeaderHeight = 48.dp
private val SectorsHeight = 56.dp
private val LapCaptionWidth = 64.dp
private const val STAT_COLUMNS = 3

/** Weights of the lap table columns: the numerals are right-aligned in their slot, the lap number and the mark are not. */
private const val LAP_WEIGHT = 0.6f
private const val TIME_WEIGHT = 1.4f
private const val DELTA_WEIGHT = 1.1f
private const val SPEED_WEIGHT = 1.1f
private const val RPM_WEIGHT = 1.1f
private const val THROTTLE_WEIGHT = 1.3f
private const val MARK_WEIGHT = 0.9f

/** The statistics as a grid of fields, [STAT_COLUMNS] to a row; a short last row keeps empty cells. */
@Composable
internal fun StatsTable(cells: List<StatCell>) {
    cells.chunked(STAT_COLUMNS).forEach { rowCells ->
        Row(Modifier.fillMaxWidth().height(StatCellHeight)) {
            repeat(STAT_COLUMNS) { column ->
                if (column > 0) VerticalRule()
                val cell = rowCells.getOrNull(column)
                if (cell == null) Spacer(Modifier.weight(1f)) else StatCellView(cell, Modifier.weight(1f).fillMaxHeight())
            }
        }
        HorizontalRule()
    }
}

/** Label and unit on top, the value right-aligned below, like a dashboard field in the size of a table cell. */
@Composable
private fun StatCellView(cell: StatCell, modifier: Modifier) {
    val colors = KLineTheme.colors
    Column(modifier.padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            FieldCaption(cell.label.uppercase())
            cell.unit?.let { FieldCaption(it.uppercase()) }
        }
        Text(
            cell.value,
            style = KLineType.rowValue,
            color = if (cell.value == MISSING_VALUE) colors.inkFaint else colors.ink,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.fillMaxWidth().capHeightBox(KLineType.rowValue.fontSize),
        )
    }
}

/**
 * The lap table: lap number, time, difference to the best lap, highest speed and rpm, share of
 * full throttle, and the mark (BEST in green; INVALID where the GPS dropped out). The chosen row has
 * the white bar at its left edge like every list choice; a tap chooses a lap.
 */
@Composable
internal fun LapTable(rows: List<LapRow>, selectedNumber: Int?, onSelect: (Int) -> Unit) {
    LapHeader()
    HorizontalRule()
    rows.forEach { row ->
        SelectableRow(selected = row.number == selectedNumber, onClick = { onSelect(row.number) }) {
            LapRowCells(row)
        }
    }
}

@Composable
private fun LapHeader() {
    Row(Modifier.fillMaxWidth().height(LapHeaderHeight).padding(horizontal = Spacing.fieldPaddingHorizontal), verticalAlignment = Alignment.CenterVertically) {
        HeaderCell("Lap", LAP_WEIGHT, TextAlign.Start)
        HeaderCell("Time", TIME_WEIGHT, TextAlign.End)
        HeaderCell("Delta", DELTA_WEIGHT, TextAlign.End)
        HeaderCell("Max km/h", SPEED_WEIGHT, TextAlign.End)
        HeaderCell("Max rpm", RPM_WEIGHT, TextAlign.End)
        HeaderCell("Full thr. %", THROTTLE_WEIGHT, TextAlign.End)
        HeaderCell("", MARK_WEIGHT, TextAlign.End)
    }
}

@Composable
private fun RowScope.HeaderCell(label: String, weight: Float, align: TextAlign) {
    Text(
        label.uppercase(),
        style = KLineType.fieldLabel,
        color = KLineTheme.colors.inkMuted,
        textAlign = align,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun RowScope.LapRowCells(row: LapRow) {
    val colors = KLineTheme.colors
    val timeColor = if (row.mark == LapMark.INVALID) colors.inkFaint else colors.ink
    NumberCell("${row.number}", LAP_WEIGHT, colors.inkMuted, TextAlign.Start)
    NumberCell(row.time, TIME_WEIGHT, timeColor, TextAlign.End)
    NumberCell(row.delta, DELTA_WEIGHT, colors.ink, TextAlign.End)
    NumberCell(row.maxSpeed, SPEED_WEIGHT, colors.ink, TextAlign.End)
    NumberCell(row.maxRpm, RPM_WEIGHT, colors.ink, TextAlign.End)
    NumberCell(row.fullThrottle, THROTTLE_WEIGHT, colors.ink, TextAlign.End)
    Box(Modifier.weight(MARK_WEIGHT), contentAlignment = Alignment.CenterEnd) {
        when (row.mark) {
            LapMark.BEST -> Text("BEST", style = KLineType.fieldLabel, color = colors.ok, maxLines = 1)
            LapMark.INVALID -> Text("NO GPS", style = KLineType.fieldLabel, color = colors.inkMuted, maxLines = 1)
            LapMark.NONE -> Unit
        }
    }
}

@Composable
private fun RowScope.NumberCell(text: String, weight: Float, color: Color, align: TextAlign) {
    Text(
        text,
        style = KLineType.rowValue,
        color = if (text == MISSING_VALUE) KLineTheme.colors.inkFaint else color,
        textAlign = align,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.weight(weight),
    )
}

/** The sector times of the selected lap, "S1  0:31.234" each, wrapping onto further lines when there are many (see [detailSplitLines]). */
@Composable
internal fun SectorTimes(lapNumber: Int, sectors: List<String>) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = SectorsHeight).padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid),
        horizontalArrangement = Arrangement.spacedBy(Spacing.fieldPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FieldCaption("LAP $lapNumber", Modifier.width(LapCaptionWidth))
        SplitCells(detailSplitLines(sectors.mapIndexed { index, time -> SectorSplit("S${index + 1}", time) }), Modifier.weight(1f))
    }
    HorizontalRule()
}
