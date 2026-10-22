package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

/** The widest text each column shows with every digit as "8": the slots of the numbers never change size. */
private const val DURATION_SAMPLE = "8:88:88"
private const val DISTANCE_SAMPLE = "888.8"
private const val RPM_SAMPLE = "8888"

private val UnitWidth = 40.dp
private val UnitGap = 6.dp
private val ColumnGap = 28.dp
private val DeleteCellWidth = 120.dp
private val HeaderHeight = 56.dp

/** Widths of the three number columns: a slot for the value and, for distance and rpm, room for the unit. */
private class TripColumns(val duration: Dp, val distance: Dp, val rpm: Dp) {
    val totalWidth: Dp = duration + ColumnGap + distance + UnitGap + UnitWidth + ColumnGap + rpm + UnitGap + UnitWidth
}

@Composable
private fun rememberTripColumns(): TripColumns {
    val duration = rememberTextWidth(DURATION_SAMPLE, KLineType.rowValue)
    val distance = rememberTextWidth(DISTANCE_SAMPLE, KLineType.rowValue)
    val rpm = rememberTextWidth(RPM_SAMPLE, KLineType.rowValue)
    return remember(duration, distance, rpm) { TripColumns(duration, distance, rpm) }
}

/**
 * The trips, newest first, as a table: when it started, how long, how far, the highest rpm, and a
 * delete cell. [rows] is null until the first read. The empty text depends on whether trips start by
 * themselves.
 */
@Composable
internal fun TripList(rows: List<TripRow>?, isAutoRecord: Boolean, onDelete: (TripRow) -> Unit, modifier: Modifier) {
    when {
        rows == null -> Box(modifier)
        rows.isEmpty() -> Box(modifier) {
            EmptyState(stringResource(if (isAutoRecord) R.string.trips_empty_auto else R.string.trips_empty_manual))
        }
        else -> TripTable(rows, onDelete, modifier)
    }
}

@Composable
private fun TripTable(rows: List<TripRow>, onDelete: (TripRow) -> Unit, modifier: Modifier) {
    val columns = rememberTripColumns()
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(HeaderHeight)) {
            Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = Spacing.fieldPaddingHorizontal), contentAlignment = Alignment.CenterStart) {
                HeaderLabel(R.string.trips_column_start, Modifier, TextAlign.Start)
            }
            Box(
                Modifier.fillMaxHeight().padding(end = Spacing.fieldPaddingHorizontal).width(columns.totalWidth),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeaderLabel(R.string.trips_column_duration, Modifier.width(columns.duration), TextAlign.End)
                    Spacer(Modifier.width(ColumnGap))
                    HeaderLabel(R.string.trips_column_distance, Modifier.width(columns.distance), TextAlign.End)
                    Spacer(Modifier.width(UnitGap + UnitWidth + ColumnGap))
                    HeaderLabel(R.string.trips_column_max_rpm, Modifier.width(columns.rpm), TextAlign.End)
                    Spacer(Modifier.width(UnitGap + UnitWidth))
                }
            }
            Spacer(Modifier.width(DeleteCellWidth + Spacing.ruleThickness))
        }
        HorizontalRule()
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { row ->
                TripRowView(row, columns, onDelete)
                HorizontalRule()
            }
        }
    }
}

@Composable
private fun HeaderLabel(labelRes: Int, modifier: Modifier, align: TextAlign) {
    Text(
        stringResource(labelRes).uppercase(),
        style = KLineType.fieldLabel,
        color = KLineTheme.colors.inkMuted,
        textAlign = align,
        maxLines = 1,
        softWrap = false,
        // Aligned with the right edge of the numbers; a label wider than its slot grows to the left.
        modifier = modifier.wrapContentWidth(Alignment.End, unbounded = true),
    )
}

/** The texts of a row share one baseline, so the row reads as a line; the delete cell is the full height. */
@Composable
private fun TripRowView(row: TripRow, columns: TripColumns, onDelete: (TripRow) -> Unit) {
    val colors = KLineTheme.colors
    Row(Modifier.fillMaxWidth().height(Spacing.touchMin)) {
        Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = Spacing.fieldPaddingHorizontal), contentAlignment = Alignment.CenterStart) {
            Row(Modifier.fillMaxWidth()) {
                Text(row.start, style = KLineType.body, color = colors.ink, maxLines = 1, modifier = Modifier.weight(1f).alignByBaseline())
                NumberText(row.duration, columns.duration)
                Spacer(Modifier.width(ColumnGap))
                NumberText(row.distance, columns.distance)
                UnitText(stringResource(R.string.unit_km))
                Spacer(Modifier.width(ColumnGap))
                NumberText(row.maxRpm, columns.rpm)
                UnitText(stringResource(R.string.unit_rpm))
            }
        }
        VerticalRule()
        Box(Modifier.width(DeleteCellWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (row.isRecording) {
                Text(stringResource(R.string.trips_state_recording).uppercase(), style = KLineType.fieldLabel, color = colors.danger, maxLines = 1)
            } else {
                DangerTextButton(stringResource(R.string.trips_delete), { onDelete(row) }, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun RowScope.NumberText(value: String, slotWidth: Dp) {
    Text(
        value,
        style = KLineType.rowValue,
        color = if (value == MISSING_VALUE) KLineTheme.colors.inkFaint else KLineTheme.colors.ink,
        textAlign = TextAlign.End,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.width(slotWidth).alignByBaseline(),
    )
}

@Composable
private fun RowScope.UnitText(unit: String) {
    Text(
        unit,
        style = KLineType.caption,
        color = KLineTheme.colors.inkMuted,
        maxLines = 1,
        modifier = Modifier.padding(start = UnitGap).width(UnitWidth).alignByBaseline(),
    )
}
