package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.diag.DiagnosticReport
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.LocalScreenProfile
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private const val DEMAND_OWNER = "diagnostics"

/** The clear flow checks the engine against this: keep rpm fresh while the page is open. */
private val RpmDemand = setOf(RPM_ID)
private val StripHeight = 96.dp
private val CompactStripHeight = 72.dp
private val StateTextMaxWidth = 560.dp
private const val MIL_WEIGHT = 1f
private const val STORED_WEIGHT = 1f
private const val LAST_READ_WEIGHT = 1.4f
private const val READ_WEIGHT = 1f
private const val CLEAR_WEIGHT = 1.1f
private val StripLook = ValueLook(KLineType.fieldValue)

/**
 * Fault codes of the car: a strip with the check-engine lamp and the two actions, the code lists
 * on the left, readiness monitors, freeze frame and vehicle on the right. Reads once when opened
 * on a link; after that only when asked.
 */
@Composable
fun DiagnosticsScreen(onOpenConnection: () -> Unit, onOpenCar: () -> Unit) {
    val vm = graphViewModel { DiagnosticsViewModel.forGraph(it) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val poller by vm.poller.collectAsStateWithLifecycle()
    DemandWhileVisible(poller, DEMAND_OWNER, RpmDemand)
    LaunchedEffect(ui.isConnected, ui.hasLink) { vm.readOnce() }

    // A confirmation must not outlive the page: leaving it cancels the clear flow.
    DisposableEffect(vm) { onDispose(vm::dismissDialog) }

    Box(Modifier.fillMaxSize()) {
        DiagnosticsContent(ui, vm, onOpenConnection, onOpenCar)
        ClearDialogHost(ui.dialog, vm)
    }
}

@Composable
private fun DiagnosticsContent(ui: DiagnosticsUiState, vm: DiagnosticsViewModel, onOpenConnection: () -> Unit, onOpenCar: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopStrip(
            ui, onRead = vm::read, onClear = vm::requestClear,
            modifier = Modifier.fillMaxWidth().height(if (LocalScreenProfile.current.isCompact) CompactStripHeight else StripHeight),
        )
        HorizontalRule()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val report = ui.report
            val lists = ui.codeLists
            when (diagnosticsPane(ui.isConnected, ui.busy, report != null, ui.readFailed)) {
                DiagnosticsPane.NOT_CONNECTED -> EmptyState(
                    message = stringResource(R.string.diag_not_connected),
                    actionLabel = stringResource(R.string.btn_go_to_connection),
                    onAction = onOpenConnection,
                )
                DiagnosticsPane.READING -> BusyPane(stringResource(R.string.diag_reading), stringResource(R.string.diag_reading_hint))
                DiagnosticsPane.CLEARING -> BusyPane(stringResource(R.string.diag_clearing), stringResource(R.string.diag_clearing_hint))
                DiagnosticsPane.FAILED -> EmptyState(
                    message = stringResource(R.string.diag_failed),
                    actionLabel = stringResource(R.string.btn_diag_read_again),
                    onAction = vm::read,
                )
                DiagnosticsPane.REPORT -> if (report != null && lists != null) ReportBody(ui, report, lists, vm, onOpenCar)
            }
        }
    }
}

@Composable
private fun ReportBody(ui: DiagnosticsUiState, report: DiagnosticReport, lists: CodeLists, vm: DiagnosticsViewModel, onOpenCar: () -> Unit) {
    // A short screen scrolls the vehicle with the codes instead of pinning it under them, which would leave one row of codes.
    val isVehicleScrolling = LocalScreenProfile.current.isCompact
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            CodeListsColumn(
                ui, lists, onSelect = vm::select, onOpenCar = onOpenCar, modifier = Modifier.weight(1f).fillMaxWidth(),
                scrollingVehicle = report.vehicleInfo.takeIf { isVehicleScrolling },
            )
            if (!isVehicleScrolling) VehiclePanel(report.vehicleInfo)
        }
        VerticalRule()
        ReportSidePanels(report, ui.selected, Modifier.weight(1f).fillMaxHeight())
    }
}

/** Lamp, stored count and read time as fields like the dashboard's, then the two actions as 72 dp buttons. */
@Composable
private fun TopStrip(ui: DiagnosticsUiState, onRead: () -> Unit, onClear: () -> Unit, modifier: Modifier) {
    val colors = KLineTheme.colors
    val report = ui.report
    val words = rememberOnOffWords()
    Row(modifier) {
        StripField(
            label = stringResource(R.string.diag_label_mil),
            value = report?.let { if (it.isMilOn) words.on.uppercase() else words.off.uppercase() },
            modifier = Modifier.weight(MIL_WEIGHT),
            color = if (report?.isMilOn == true) colors.danger else colors.ink,
        )
        VerticalRule()
        StripField(stringResource(R.string.diag_label_stored), report?.stored?.codes?.size?.toString(), Modifier.weight(STORED_WEIGHT))
        VerticalRule()
        StripField(stringResource(R.string.diag_label_last_read), report?.let { formatClockTime(it.readAtMs) }, Modifier.weight(LAST_READ_WEIGHT))
        VerticalRule()
        ActionCell(Modifier.weight(READ_WEIGHT)) {
            PrimaryButton(
                stringResource(R.string.btn_diag_read), onRead, Modifier.fillMaxSize(),
                enabled = canRead(ui.isConnected, ui.busy),
            )
        }
        VerticalRule()
        ActionCell(Modifier.weight(CLEAR_WEIGHT)) {
            DangerButton(
                stringResource(R.string.btn_diag_clear), onClear, Modifier.fillMaxSize(),
                enabled = canClear(ui.isConnected, ui.busy, report != null),
            )
        }
    }
}

/** A field of the strip like the dashboard's; "--" until the first read. */
@Composable
private fun StripField(label: String, value: String?, modifier: Modifier, color: Color = KLineTheme.colors.ink) {
    val shown = value ?: MISSING_VALUE
    ValueField(label, unit = null, shown, StripLook, slotSample = shown, modifier.fillMaxHeight(), color)
}

@Composable
private fun ActionCell(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxHeight().padding(Spacing.fieldPaddingHorizontal), contentAlignment = Alignment.Center) { content() }
}

/** A running read or clear: what is going on, how long it has taken, and what the user should leave alone. */
@Composable
private fun BusyPane(title: String, hint: String) {
    val colors = KLineTheme.colors
    val startMs = remember { System.currentTimeMillis() }
    val nowMs by rememberNowMs()
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.grid * 2, Alignment.CenterVertically),
    ) {
        Text(title, style = KLineType.title, color = colors.ink)
        Text(stringResource(R.string.diag_elapsed, elapsedSeconds(startMs, nowMs).toString()), style = KLineType.fieldValue, color = colors.inkMuted)
        Text(hint, style = KLineType.body, color = colors.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = StateTextMaxWidth))
    }
}
