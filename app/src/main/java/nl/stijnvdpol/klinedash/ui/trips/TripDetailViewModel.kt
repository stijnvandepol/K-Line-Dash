package nl.stijnvdpol.klinedash.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.AppGraph
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.service.AnalysisResult
import nl.stijnvdpol.klinedash.service.DeleteResult
import nl.stijnvdpol.klinedash.service.LapTrace
import nl.stijnvdpol.klinedash.service.TripAnalysisRepository
import nl.stijnvdpol.klinedash.service.TripDetail
import nl.stijnvdpol.klinedash.service.TripExporter
import nl.stijnvdpol.klinedash.service.TripLibrary
import nl.stijnvdpol.klinedash.ui.STILL_RECORDING_NOTICE

private const val STOP_TIMEOUT_MS = 5_000L

/** What the detail page has of the trip with [tripId]. */
internal sealed interface DetailState {
    data object Loading : DetailState
    data class Loaded(val detail: TripDetail) : DetailState
    data class Failed(val reason: String) : DetailState
}

/** The graph of the selected lap. */
internal sealed interface LapTraceState {
    data object None : LapTraceState
    data object Loading : LapTraceState
    data class Ready(val lapNumber: Int, val trace: LapTrace) : LapTraceState
    data object Unavailable : LapTraceState
}

/** The export panel of the top strip. */
internal sealed interface ExportUiState {
    data object Closed : ExportUiState
    data object Choosing : ExportUiState
    data object Working : ExportUiState
    data class Done(val outcome: ExportOutcome) : ExportUiState
    data class Failed(val reason: String) : ExportUiState
}

/**
 * The trip detail page: statistics, laps and route of one trip, the graph of the selected lap, export
 * for PC and delete. One instance serves every trip; [open] resets it for the trip shown.
 */
internal class TripDetailViewModel(
    private val analysis: TripAnalysisRepository,
    private val exporter: TripExporter,
    private val library: TripLibrary,
    trips: Flow<List<Trip>>,
) : ViewModel() {
    /** The trip rows, null until the first read; the row of the open trip gives its date, and it is missing once the trip is deleted. */
    val trips: StateFlow<List<Trip>?> = trips.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private var tripId: Long? = null

    private val _detail = MutableStateFlow<DetailState>(DetailState.Loading)
    val detail: StateFlow<DetailState> = _detail.asStateFlow()

    private val _selectedLap = MutableStateFlow<Int?>(null)

    /** Number of the selected lap; the best lap when the trip opens. */
    val selectedLap: StateFlow<Int?> = _selectedLap.asStateFlow()

    private val _lapTrace = MutableStateFlow<LapTraceState>(LapTraceState.None)
    val lapTrace: StateFlow<LapTraceState> = _lapTrace.asStateFlow()

    private val _export = MutableStateFlow<ExportUiState>(ExportUiState.Closed)
    val export: StateFlow<ExportUiState> = _export.asStateFlow()

    private val _deleteError = MutableStateFlow<String?>(null)
    val deleteError: StateFlow<String?> = _deleteError.asStateFlow()

    private var traceJob: Job? = null

    fun open(id: Long) {
        if (tripId == id && _detail.value !is DetailState.Failed) return
        tripId = id
        traceJob?.cancel()
        _detail.value = DetailState.Loading
        _selectedLap.value = null
        _lapTrace.value = LapTraceState.None
        _export.value = ExportUiState.Closed
        _deleteError.value = null
        viewModelScope.launch { load(id) }
    }

    private suspend fun load(id: Long) {
        when (val result = analysis.analyze(id)) {
            is AnalysisResult.Done -> {
                _detail.value = DetailState.Loaded(result.detail)
                (bestLapOf(result.detail.laps) ?: result.detail.laps.firstOrNull())?.let { selectLap(it.lap.number) }
            }
            AnalysisResult.TripNotFound -> _detail.value = DetailState.Failed(TRIP_GONE)
            is AnalysisResult.Failed -> _detail.value = DetailState.Failed(result.reason)
        }
    }

    fun selectLap(number: Int) {
        val loaded = _detail.value as? DetailState.Loaded ?: return
        val lap = loaded.detail.laps.firstOrNull { it.lap.number == number }?.lap ?: return
        _selectedLap.value = number
        _lapTrace.value = LapTraceState.Loading
        traceJob?.cancel()
        traceJob = viewModelScope.launch {
            val trace = analysis.lapTrace(loaded.detail.tripId, lap)
            _lapTrace.value = trace?.let { LapTraceState.Ready(number, it) } ?: LapTraceState.Unavailable
        }
    }

    fun toggleExportChoices() {
        _export.value = if (_export.value == ExportUiState.Choosing) ExportUiState.Closed else ExportUiState.Choosing
    }

    fun export(choice: ExportChoice) {
        val id = tripId ?: return
        if (_export.value == ExportUiState.Working) return
        _export.value = ExportUiState.Working
        viewModelScope.launch {
            _export.value = when (val outcome = exportOutcome(exporter.exportTrip(id, choice.formats))) {
                is ExportOutcome.Failed -> ExportUiState.Failed(outcome.reason)
                is ExportOutcome.Written -> ExportUiState.Done(outcome)
            }
        }
    }

    /** The list drops the trip when it is gone; the screen then leaves. A refused delete shows in [deleteError]. */
    fun delete() {
        val id = tripId ?: return
        viewModelScope.launch {
            when (val result = library.delete(id)) {
                is DeleteResult.Failed -> _deleteError.value = result.reason
                DeleteResult.IsRecording -> _deleteError.value = STILL_RECORDING_NOTICE
                DeleteResult.Deleted, DeleteResult.NotFound -> Unit
            }
        }
    }

    companion object {
        fun forGraph(graph: AppGraph) = TripDetailViewModel(graph.tripAnalysis, graph.tripExporter, graph.tripLibrary, graph.tripLibrary.trips)
    }
}
