package nl.stijnvdpol.klinedash.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.AppGraph
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.data.DashboardLayout
import nl.stijnvdpol.klinedash.data.graphSignalIds
import nl.stijnvdpol.klinedash.ui.pages.GraphHistory
import nl.stijnvdpol.klinedash.ui.pages.PeakHold
import nl.stijnvdpol.klinedash.ui.pages.Peaks

/**
 * What the dashboard pages share and must not lose when the user visits another screen: the layout,
 * the last 60 s of every signal that has a graph, the peak hold of the track page and the page the
 * pager was on. All of it lives as long as the activity and nothing is written to disk but the layout.
 *
 * It records from [readings] whether or not the dashboard is visible; the poller only updates the
 * signals somebody demands, so a hidden dashboard records nothing new.
 */
internal class DashboardViewModel(layoutFlow: Flow<DashboardLayout>, readings: Flow<Map<String, Reading>>) : ViewModel() {
    /** Null until the first read from the database (a few milliseconds). */
    val layout: StateFlow<DashboardLayout?> =
        layoutFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val history = GraphHistory()

    private val _graphRevision = MutableStateFlow(0)

    /** Counts up whenever [history] got a new point; graphs read it in their draw phase to redraw. */
    val graphRevision: StateFlow<Int> = _graphRevision.asStateFlow()

    private var latestReadings: Map<String, Reading> = emptyMap()
    private var peakHold = PeakHold()
    private val _peaks = MutableStateFlow(Peaks())
    val peaks: StateFlow<Peaks> = _peaks.asStateFlow()

    /** The pager's page, kept here so the dashboard opens where the user left it. */
    var currentPage = 0

    init {
        val graphIds = layout.filterNotNull().map { it.graphSignalIds() }.distinctUntilChanged()
        viewModelScope.launch {
            combine(readings, graphIds) { current, ids -> current to ids }.collect { (current, ids) ->
                latestReadings = current
                if (history.record(ids, current)) _graphRevision.update { it + 1 }
                peakHold = peakHold.withReadings(current[RPM_ID], current[SPEED_ID])
                _peaks.value = peakHold.peaks
            }
        }
    }

    fun resetPeaks() {
        peakHold = peakHold.reset(latestReadings[RPM_ID], latestReadings[SPEED_ID])
        _peaks.value = peakHold.peaks
    }

    companion object {
        fun forGraph(graph: AppGraph) = DashboardViewModel(
            graph.dashboardLayoutRepository.layout,
            pollerFlow(emptyMap()) { it.readings },
        )
    }
}
