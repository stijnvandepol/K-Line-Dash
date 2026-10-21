package nl.stijnvdpol.klinedash.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.AppGraph
import nl.stijnvdpol.klinedash.core.diag.ClearOutcome
import nl.stijnvdpol.klinedash.core.diag.DiagnosticReport
import nl.stijnvdpol.klinedash.core.diag.DiagnosticsReader
import nl.stijnvdpol.klinedash.core.diag.Dtc
import nl.stijnvdpol.klinedash.core.diag.DtcDescriptions
import nl.stijnvdpol.klinedash.core.diag.ObdDiagnostics
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.obd.ObdPoller
import nl.stijnvdpol.klinedash.service.ObdServiceState

private const val TAG = "DiagnosticsViewModel"

/** The confirmation the clear flow is waiting for; [NONE] when no dialog is open. */
internal enum class ClearDialog { NONE, ENGINE_RUNNING, INTRO, FINAL }

/** What happened to the last clear request, shown above the code lists until the next read or clear. */
internal sealed interface ClearNotice {
    data object Cleared : ClearNotice

    data class Refused(val reason: String) : ClearNotice

    /** The link dropped before the ECU answered; whether anything was cleared is unknown. */
    data object LinkLost : ClearNotice
}

internal data class DiagnosticsUiState(
    val hasLink: Boolean = false,
    val isConnected: Boolean = false,
    val busy: DiagnosticsBusy? = null,
    val report: DiagnosticReport? = null,
    val codeLists: CodeLists? = null,
    val selected: Dtc? = null,
    val readFailed: Boolean = false,
    val dialog: ClearDialog = ClearDialog.NONE,
    val notice: ClearNotice? = null,
)

/**
 * Owns the diagnostics reader of the current link and the clear-codes flow. A read happens once
 * when the page is first opened on a link and whenever the user asks; clearing is only reachable
 * through two confirmations, re-checks that the engine is off at the last moment and is sent once,
 * never retried.
 */
internal class DiagnosticsViewModel(
    private val loadDescriptions: suspend () -> DtcDescriptions,
    pollerFlow: StateFlow<ObdPoller?>,
    connectedFlow: Flow<Boolean>,
    private val createReader: (ObdPoller) -> DiagnosticsReader,
    private val clock: () -> Long,
) : ViewModel() {
    private class Session(val poller: ObdPoller, val reader: DiagnosticsReader)

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    /** The poller of the current link, for the screen's rpm demand. */
    val poller: StateFlow<ObdPoller?> = pollerFlow

    private var session: Session? = null
    private var work: Job? = null
    private var hasAutoRead = false

    init {
        viewModelScope.launch { pollerFlow.collectLatest(::onPollerChanged) }
        viewModelScope.launch {
            connectedFlow.collect(::onConnectionChanged)
        }
    }

    /**
     * A dropped link ends the session: what was read belongs to a connection that is gone, and an
     * unfinished read or clear cannot be trusted. The next connection reads again when the page is open.
     */
    private fun onConnectionChanged(connected: Boolean) {
        if (connected) {
            _state.update { it.copy(isConnected = true) }
            return
        }
        work?.cancel()
        work = null
        hasAutoRead = false
        _state.update { DiagnosticsUiState(hasLink = it.hasLink, isConnected = false) }
    }

    /** A new link means a new car: nothing read from the old one may stay on screen. */
    private fun onPollerChanged(poller: ObdPoller?) {
        work?.cancel()
        work = null
        hasAutoRead = false
        session = poller?.let { Session(it, createReader(it)) }
        _state.update { DiagnosticsUiState(hasLink = poller != null, isConnected = it.isConnected) }
    }

    /** The first read after the page opens on a link; later visits keep what was read. */
    fun readOnce() {
        val current = _state.value
        if (hasAutoRead || !current.hasLink || !current.isConnected || current.busy != null) return
        hasAutoRead = true
        read()
    }

    fun read() {
        val current = session ?: return
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = DiagnosticsBusy.READING, readFailed = false, notice = null) }
        work = viewModelScope.launch {
            try {
                showReport(current.reader.read())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The screen says "not finished, read again"; the cause belongs in the log, not on screen.
                Log.w(TAG, "Reading diagnostics failed", e)
                _state.update { it.copy(busy = null, report = null, codeLists = null, selected = null, readFailed = true) }
            }
        }
    }

    private suspend fun showReport(report: DiagnosticReport, notice: ClearNotice? = null) {
        val lists = codeLists(report, loadDescriptions())
        _state.update {
            it.copy(
                busy = null,
                report = report,
                codeLists = lists,
                selected = defaultSelection(report),
                readFailed = false,
                notice = notice,
            )
        }
    }

    fun select(code: Dtc) = _state.update { it.copy(selected = code) }

    /** First tap on "Clear codes": explains instead of proceeding while the engine runs. */
    fun requestClear() {
        val current = _state.value
        if (!canClear(current.isConnected, current.busy, current.report != null)) return
        _state.update { it.copy(dialog = if (isClearBlocked()) ClearDialog.ENGINE_RUNNING else ClearDialog.INTRO) }
    }

    fun continueClear() = _state.update { if (it.dialog == ClearDialog.INTRO) it.copy(dialog = ClearDialog.FINAL) else it }

    fun dismissDialog() = _state.update { it.copy(dialog = ClearDialog.NONE) }

    /** The final confirmation. The only path to service 04; the engine is checked again because time has passed. */
    fun confirmClear() {
        val current = session ?: return
        if (_state.value.dialog != ClearDialog.FINAL || _state.value.busy != null) return
        if (isClearBlocked()) {
            _state.update { it.copy(dialog = ClearDialog.ENGINE_RUNNING) }
            return
        }
        _state.update { it.copy(dialog = ClearDialog.NONE, busy = DiagnosticsBusy.CLEARING, notice = null) }
        work = viewModelScope.launch {
            try {
                when (val outcome = current.reader.clearCodes()) {
                    is ClearOutcome.Cleared -> showReport(outcome.after, ClearNotice.Cleared)
                    is ClearOutcome.Refused -> _state.update { it.copy(busy = null, notice = ClearNotice.Refused(outcome.reason)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Clearing codes failed", e)
                _state.update { it.copy(busy = null, notice = ClearNotice.LinkLost) }
            }
        }
    }

    private fun isClearBlocked(): Boolean {
        val rpm = session?.poller?.readings?.value?.get(RPM_ID)
        return isClearBlockedByEngine(rpm, clock())
    }

    companion object {
        fun forGraph(graph: AppGraph) = DiagnosticsViewModel(
            loadDescriptions = graph::awaitDtcDescriptions,
            pollerFlow = ObdServiceState.poller,
            connectedFlow = ObdServiceState.connectionState.map { it is ConnectionState.Connected },
            createReader = { ObdDiagnostics(it) },
            clock = System::currentTimeMillis,
        )
    }
}
