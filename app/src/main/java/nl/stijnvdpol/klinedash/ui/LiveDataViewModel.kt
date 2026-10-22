package nl.stijnvdpol.klinedash.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import nl.stijnvdpol.klinedash.core.alert.AlertSettings
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.obd.ObdPoller
import nl.stijnvdpol.klinedash.core.obd.PollStats
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.AppGraph
import nl.stijnvdpol.klinedash.service.ObdServiceState

private const val STOP_TIMEOUT_MS = 5_000L

/** [select] of the poller of the current link, following link rebuilds; [whenNone] while there is no poller. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> pollerFlow(whenNone: T, select: (ObdPoller) -> Flow<T>): Flow<T> =
    ObdServiceState.poller.flatMapLatest { current -> if (current == null) flowOf(whenNone) else select(current) }

/**
 * Live values for the dashboard and the sensor list: the poller of the current link, flattened so
 * the screens do not care when the link is rebuilt. WhileSubscribed: nothing is collected while no
 * screen is visible.
 */
class LiveDataViewModel(alertSettings: Flow<AlertSettings>) : ViewModel() {
    val poller: StateFlow<ObdPoller?> = ObdServiceState.poller

    val connectionState: StateFlow<ConnectionState> = ObdServiceState.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ConnectionState.Idle)

    val readings: StateFlow<Map<String, Reading>> =
        fromPoller(emptyMap()) { it.readings }

    /** Null until the car has reported which PIDs it supports on this connection. */
    val supportedPids: StateFlow<Set<Int>?> =
        fromPoller(null) { it.supportedPids }

    val stats: StateFlow<PollStats> =
        fromPoller(PollStats()) { it.stats }

    /** The warning limits; the values on the screens change colour at these (one source with the warnings). */
    val limits: StateFlow<AlertSettings> =
        alertSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AlertSettings())

    private fun <T> fromPoller(whenNone: T, select: (ObdPoller) -> Flow<T>): StateFlow<T> =
        pollerFlow(whenNone, select).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), whenNone)

    companion object {
        fun forGraph(graph: AppGraph) = LiveDataViewModel(graph.alertSettingsRepository.settings)
    }
}
