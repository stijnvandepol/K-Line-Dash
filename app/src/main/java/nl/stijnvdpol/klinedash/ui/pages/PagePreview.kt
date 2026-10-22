package nl.stijnvdpol.klinedash.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.data.FIELD_SLOT_COUNT
import nl.stijnvdpol.klinedash.data.GridCell
import nl.stijnvdpol.klinedash.data.GridPage
import nl.stijnvdpol.klinedash.data.MainPage
import nl.stijnvdpol.klinedash.data.Page
import nl.stijnvdpol.klinedash.data.PRIMARY_SLOT_COUNT
import nl.stijnvdpol.klinedash.data.TrackPage
import nl.stijnvdpol.klinedash.data.WidgetType
import nl.stijnvdpol.klinedash.ui.HorizontalRule
import nl.stijnvdpol.klinedash.ui.VerticalRule
import nl.stijnvdpol.klinedash.ui.fieldLabel
import nl.stijnvdpol.klinedash.ui.isAvailable
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

/** The real page is 1280 x 648 dp (720 minus the bottom bar); the preview keeps those proportions. */
private const val PAGE_ASPECT_RATIO = 1280f / 648f

private val BarBlockHeight = 48.dp
private val SmallRowHeight = 72.dp
private val PrimaryWeights = listOf(2f, 1.4f, 1f)
private const val TRACK_RPM_WEIGHT = 2f

/**
 * A scaled sketch of [page] in the layout of the real one, each editable cell showing the signal it
 * holds. Tapping a cell calls [onEditSlot] with its slot (Main: 0-2 primary, 3-8 small; Grid: cell
 * index); the Track page has nothing to pick. [supportedPids] dims the cells whose signal the car
 * does not report (null: not known yet, nothing is dimmed).
 */
@Composable
internal fun PagePreview(page: Page, supportedPids: Set<Int>?, onEditSlot: (Int) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        // As wide as the room allows, but never taller than it: a warning banner or a short screen shrinks the sketch.
        val width = minOf(maxWidth, maxHeight * PAGE_ASPECT_RATIO)
        Column(Modifier.width(width).height(width / PAGE_ASPECT_RATIO).border(Spacing.ruleThickness, KLineTheme.colors.rule)) {
            when (page) {
                is MainPage -> MainPreview(page, supportedPids, onEditSlot)
                is GridPage -> GridPreview(page, supportedPids, onEditSlot)
                is TrackPage -> TrackPreview()
            }
        }
    }
}

@Composable
private fun ColumnScope.MainPreview(page: MainPage, supportedPids: Set<Int>?, onEditSlot: (Int) -> Unit) {
    BarBlock()
    HorizontalRule()
    Row(Modifier.weight(1f).fillMaxWidth()) {
        for (slot in 0 until PRIMARY_SLOT_COUNT) {
            if (slot > 0) VerticalRule()
            SignalCell(page.primary[slot], null, supportedPids, { onEditSlot(slot) }, Modifier.weight(PrimaryWeights[slot]).fillMaxHeight())
        }
    }
    HorizontalRule()
    Row(Modifier.height(SmallRowHeight).fillMaxWidth()) {
        for (field in 0 until FIELD_SLOT_COUNT) {
            if (field > 0) VerticalRule()
            val slot = PRIMARY_SLOT_COUNT + field
            SignalCell(page.fields[field], null, supportedPids, { onEditSlot(slot) }, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun ColumnScope.GridPreview(page: GridPage, supportedPids: Set<Int>?, onEditSlot: (Int) -> Unit) {
    for (row in 0 until page.size.rows) {
        if (row > 0) HorizontalRule()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            for (column in 0 until page.size.columns) {
                if (column > 0) VerticalRule()
                val index = row * page.size.columns + column
                val cell: GridCell = page.cells[index]
                val widget = stringResource(if (cell.widget == WidgetType.GRAPH) R.string.widget_graph else R.string.widget_number)
                SignalCell(cell.signalId, widget, supportedPids, { onEditSlot(index) }, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** Fixed, nothing to edit: the bar, the big rpm and the side column of the track page. */
@Composable
private fun ColumnScope.TrackPreview() {
    BarBlock()
    HorizontalRule()
    Row(Modifier.weight(1f).fillMaxWidth()) {
        StaticCell(stringResource(R.string.track_preview_rpm), Modifier.weight(TRACK_RPM_WEIGHT).fillMaxHeight())
        VerticalRule()
        Column(Modifier.weight(1f).fillMaxHeight()) {
            StaticCell(stringResource(R.string.track_preview_coolant_speed), Modifier.weight(1f).fillMaxWidth())
            HorizontalRule()
            StaticCell(stringResource(R.string.track_preview_peaks), Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun BarBlock() {
    Box(Modifier.fillMaxWidth().height(BarBlockHeight).background(KLineTheme.colors.surface), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.preview_rpm_bar).uppercase(), style = KLineType.fieldLabel, color = KLineTheme.colors.inkMuted)
    }
}

@Composable
private fun StaticCell(label: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(label.uppercase(), style = KLineType.fieldLabel, color = KLineTheme.colors.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(Spacing.grid))
    }
}

/** The signal's label, an optional second line, and the whole cell is a button. A signal the car lacks is dimmed and says so. */
@Composable
private fun SignalCell(signalId: String, detail: String?, supportedPids: Set<Int>?, onClick: () -> Unit, modifier: Modifier) {
    val colors = KLineTheme.colors
    val signal = Signals.byId(signalId)
    val isUnsupported = signal == null || (supportedPids != null && !signal.isAvailable(supportedPids))
    Column(
        modifier.clickable(role = Role.Button, onClick = onClick).padding(Spacing.grid),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            signal?.let { fieldLabel(it) } ?: signalId.uppercase(),
            style = KLineType.fieldLabel,
            color = if (isUnsupported) colors.inkFaint else colors.ink,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        val second = if (isUnsupported) stringResource(R.string.preview_not_reported) else detail
        if (second != null) Text(second, style = KLineType.caption, color = colors.inkMuted, textAlign = TextAlign.Center, maxLines = 1)
    }
}
