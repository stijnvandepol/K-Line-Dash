package nl.stijnvdpol.klinedash.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.diag.DiagnosticReport
import nl.stijnvdpol.klinedash.core.diag.Dtc
import nl.stijnvdpol.klinedash.core.diag.MonitorStatus
import nl.stijnvdpol.klinedash.core.diag.ReadinessMonitor
import nl.stijnvdpol.klinedash.core.diag.VehicleInfo
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private val HeaderHeight = 32.dp
private val InfoRowHeight = 40.dp
private val UnitSlotWidth = 48.dp
private const val CODE_SLOT_SAMPLE = "P8888"
private const val FREEZE_VALUE_SAMPLE = "-88.8"
private const val GRID_COLUMNS = 2

// --- Left: the code lists -------------------------------------------------------------------

/** Stored, pending and permanent codes, each a section of rows on rules; only stored codes can be selected. */
@Composable
internal fun CodeListsColumn(ui: DiagnosticsUiState, lists: CodeLists, onSelect: (Dtc) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier) {
        ui.notice?.let { notice -> item(key = "notice") { NoticeBanner(notice) } }
        codeSection(R.string.diag_section_stored, "stored", lists.stored, ui.selected, onSelect)
        codeSection(R.string.diag_section_pending, "pending", lists.pending, null, null)
        codeSection(R.string.diag_section_permanent, "permanent", lists.permanent, null, null)
    }
}

private fun LazyListScope.codeSection(
    titleRes: Int,
    key: String,
    state: CodeListState,
    selected: Dtc?,
    onSelect: ((Dtc) -> Unit)?,
) {
    item(key = key) {
        val count = (state as? CodeListState.Codes)?.rows?.size ?: if (state == CodeListState.Empty) 0 else null
        PanelHeader(stringResource(titleRes), count?.toString() ?: MISSING_VALUE)
    }
    when (state) {
        is CodeListState.Codes -> items(state.rows, key = { "$key:${it.code.code}" }) { row ->
            CodeRowView(row, isSelected = row.code == selected, onSelect = onSelect?.let { select -> { select(row.code) } })
        }
        CodeListState.Empty -> item(key = "$key:state") { StateRow(stringResource(R.string.diag_codes_none), KLineTheme.colors.ink) }
        CodeListState.Unsupported -> item(key = "$key:state") { StateRow(stringResource(R.string.diag_codes_unsupported), KLineTheme.colors.inkMuted) }
    }
}

/** A selectable row for stored codes ([onSelect] given), a plain one for the rest; both at least 72 dp. */
@Composable
private fun CodeRowView(row: CodeRow, isSelected: Boolean, onSelect: (() -> Unit)?) {
    if (onSelect != null) {
        SelectableRow(selected = isSelected, onClick = onSelect) { CodeRowContent(row) }
    } else {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Spacing.touchMin).padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid),
            verticalAlignment = Alignment.CenterVertically,
        ) { CodeRowContent(row) }
        HorizontalRule()
    }
}

/** The code large in a fixed slot, so the descriptions of all rows start at the same x. */
@Composable
private fun RowScope.CodeRowContent(row: CodeRow) {
    val colors = KLineTheme.colors
    val slot = rememberTextWidth(CODE_SLOT_SAMPLE, KLineType.fieldValue)
    Text(
        row.code.code,
        style = KLineType.fieldValue,
        color = colors.ink,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.width(slot).capHeightBox(KLineType.fieldValue.fontSize),
    )
    Column(Modifier.weight(1f).padding(start = Spacing.fieldPaddingHorizontal)) {
        row.description?.let { Text(it, style = KLineType.body, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        row.note?.let { Text(it, style = KLineType.caption, color = colors.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun StateRow(text: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.listRowMin).padding(horizontal = Spacing.fieldPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = KLineType.body, color = color)
    }
    HorizontalRule()
}

/** What happened to the last clear request. Danger colour for anything that did not clear. */
@Composable
private fun NoticeBanner(notice: ClearNotice) {
    val colors = KLineTheme.colors
    val (text, color) = when (notice) {
        ClearNotice.Cleared -> stringResource(R.string.diag_notice_cleared) to colors.ink
        is ClearNotice.Refused -> stringResource(R.string.diag_notice_refused, notice.reason) to colors.danger
        ClearNotice.LinkLost -> stringResource(R.string.diag_notice_link_lost) to colors.danger
    }
    Text(
        text,
        style = KLineType.body,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(Spacing.fieldPaddingHorizontal),
    )
    HorizontalRule()
}

// --- Right: readiness, freeze frame, vehicle ----------------------------------------------

/** Scrolls only if a car reports more monitors and values than fit next to the code lists. */
@Composable
internal fun ReportSidePanels(report: DiagnosticReport, selected: Dtc?, modifier: Modifier = Modifier) {
    val words = rememberOnOffWords()
    Column(modifier.verticalScroll(rememberScrollState())) {
        ReadinessPanel(report.monitors)
        FreezeFramePanel(freezeFrameView(report.freezeFrame, selected, words))
    }
}

@Composable
private fun ReadinessPanel(monitors: List<ReadinessMonitor>) {
    val summary = readinessSummary(monitors)
    PanelHeader(
        stringResource(R.string.diag_section_readiness),
        if (monitors.isEmpty()) MISSING_VALUE else stringResource(R.string.diag_readiness_summary, summary.ready.toString(), summary.applicable.toString()),
    )
    if (monitors.isEmpty()) StateRow(stringResource(R.string.diag_readiness_none), KLineTheme.colors.inkMuted)
    GridRows(monitors) { monitor -> MonitorCell(monitor) }
}

@Composable
private fun RowScope.MonitorCell(monitor: ReadinessMonitor) {
    val colors = KLineTheme.colors
    val (status, statusColor) = when (monitor.status) {
        MonitorStatus.READY -> stringResource(R.string.diag_monitor_ready) to colors.ink
        MonitorStatus.NOT_READY -> stringResource(R.string.diag_monitor_not_ready) to colors.caution
        MonitorStatus.NOT_APPLICABLE -> stringResource(R.string.diag_monitor_na) to colors.inkFaint
    }
    val nameColor = if (monitor.status == MonitorStatus.NOT_APPLICABLE) colors.inkMuted else colors.ink
    Text(monitorLabel(monitor.id), style = KLineType.body, color = nameColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    Spacer(Modifier.width(Spacing.grid))
    Text(status, style = KLineType.body, color = statusColor, maxLines = 1, softWrap = false)
}

@Composable
private fun FreezeFramePanel(view: FreezeFrameView) {
    val colors = KLineTheme.colors
    val trigger = when (view) {
        is FreezeFrameView.Values -> view.triggerCode?.code
        is FreezeFrameView.OtherCode -> view.triggerCode.code
        FreezeFrameView.None -> null
    }
    PanelHeader(stringResource(R.string.diag_section_freeze), trigger ?: MISSING_VALUE)
    when (view) {
        FreezeFrameView.None -> StateRow(stringResource(R.string.diag_freeze_none), colors.inkMuted)
        is FreezeFrameView.OtherCode -> StateRow(stringResource(R.string.diag_freeze_other, view.selected.code, view.triggerCode.code), colors.inkMuted)
        is FreezeFrameView.Values -> GridRows(view.rows) { row -> FreezeCell(row) }
    }
}

@Composable
private fun RowScope.FreezeCell(row: FreezeRow) {
    val colors = KLineTheme.colors
    val valueWidth = rememberTextWidth(FREEZE_VALUE_SAMPLE, KLineType.rowValue)
    Text(row.name, style = KLineType.body, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).alignByBaseline())
    Text(
        row.value,
        style = KLineType.rowValue,
        color = colors.ink,
        textAlign = TextAlign.End,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.width(valueWidth).alignByBaseline(),
    )
    Text(
        row.unit,
        style = KLineType.caption,
        color = colors.inkMuted,
        maxLines = 1,
        modifier = Modifier.padding(start = Spacing.grid).width(UnitSlotWidth).alignByBaseline(),
    )
}

/** VIN and ECU name, pinned under the code lists. A value the car does not report says so in muted text. */
@Composable
internal fun VehiclePanel(info: VehicleInfo, modifier: Modifier = Modifier) {
    // Values start where the code descriptions above them start, so the left column has one text edge.
    val labelWidth = rememberTextWidth(CODE_SLOT_SAMPLE, KLineType.fieldValue) + Spacing.fieldPaddingHorizontal
    Column(modifier) {
        PanelHeader(stringResource(R.string.diag_section_vehicle), null)
        VehicleRow(stringResource(R.string.diag_vin), info.vin, labelWidth, withRule = true)
        // No rule under the last row: the navigation bar draws the line.
        VehicleRow(stringResource(R.string.diag_ecu), info.ecuName, labelWidth, withRule = false)
    }
}

@Composable
private fun VehicleRow(label: String, value: String?, labelWidth: Dp, withRule: Boolean) {
    val colors = KLineTheme.colors
    val reported = value?.takeIf { it.isNotBlank() }
    Row(
        Modifier.fillMaxWidth().height(InfoRowHeight).padding(horizontal = Spacing.fieldPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = KLineType.fieldLabel, color = colors.inkMuted, modifier = Modifier.width(labelWidth))
        Text(
            reported ?: stringResource(R.string.diag_not_reported),
            style = KLineType.body,
            color = if (reported != null) colors.ink else colors.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (withRule) HorizontalRule()
}

// --- Shared ---------------------------------------------------------------------------------

/** Caps label on the left, a short value on the right, a rule below. */
@Composable
private fun PanelHeader(title: String, trailing: String?) {
    val colors = KLineTheme.colors
    Row(
        Modifier.fillMaxWidth().height(HeaderHeight).padding(horizontal = Spacing.fieldPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title.uppercase(), style = KLineType.fieldLabel, color = colors.inkMuted, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing.uppercase(), style = KLineType.fieldLabel, color = colors.ink, maxLines = 1)
    }
    HorizontalRule()
}

/** [items] as rows of two cells separated by a vertical rule, a rule under each row. */
@Composable
private fun <T> GridRows(items: List<T>, cell: @Composable RowScope.(T) -> Unit) {
    items.chunked(GRID_COLUMNS).forEach { pair ->
        Row(Modifier.fillMaxWidth().height(InfoRowHeight)) {
            pair.forEachIndexed { index, item ->
                if (index > 0) VerticalRule()
                // Centred as a whole: cells that align by baseline would otherwise sit at the top of the row.
                Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = Spacing.fieldPaddingHorizontal), contentAlignment = Alignment.CenterStart) {
                    Row(Modifier.fillMaxWidth()) { cell(item) }
                }
            }
            if (pair.size < GRID_COLUMNS) {
                VerticalRule()
                Box(Modifier.weight(1f))
            }
        }
        HorizontalRule()
    }
}
