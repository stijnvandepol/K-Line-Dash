package nl.stijnvdpol.klinedash.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.AppGraph
import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.transport.TransportKind
import nl.stijnvdpol.klinedash.data.ConnectionSettings
import nl.stijnvdpol.klinedash.data.GpsSettingsRepository
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.service.GpsStatus
import nl.stijnvdpol.klinedash.service.LapSessions
import nl.stijnvdpol.klinedash.service.LocationSource

private const val STOP_TIMEOUT_MS = 5_000L

/**
 * SETUP > GPS & Tracks: the state of the GPS, the simulated circuit, and the saved tracks. The tracks
 * are edited through [LapSessions], which also recomputes the trips on a changed track.
 */
internal class GpsTracksViewModel(
    private val location: LocationSource,
    private val sessions: LapSessions,
    private val gpsSettings: GpsSettingsRepository,
    trips: Flow<List<Trip>>,
    connection: Flow<ConnectionSettings>,
) : ViewModel() {
    private val whileObserved = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS)

    val status: StateFlow<GpsStatus> = location.status

    val fix: StateFlow<GeoFix?> = location.fix

    /** The simulated circuit replaces the device GPS only with the simulator connection; the switch is only offered then. */
    val isSimulatorConnection: StateFlow<Boolean> = connection.map { it.kind == TransportKind.SIMULATOR }
        .distinctUntilChanged()
        .stateIn(viewModelScope, whileObserved, false)

    val useSimulated: StateFlow<Boolean> = gpsSettings.settings.map { it.useSimulatedGps }
        .stateIn(viewModelScope, whileObserved, false)

    /** Null until the first read, so the empty text does not flash up. */
    val tracks: StateFlow<List<TrackRowModel>?> = combine(sessions.allTracks, sessions.activeTrack, trips, ::trackRows)
        .stateIn(viewModelScope, whileObserved, null)

    val canRename: StateFlow<Boolean> = combine(location.status, location.fix, ::isParked)
        .distinctUntilChanged()
        .stateIn(viewModelScope, whileObserved, true)

    fun setUseSimulated(isOn: Boolean) {
        viewModelScope.launch { gpsSettings.update { it.copy(useSimulatedGps = isOn) } }
    }

    /** The caller has checked the name with [trackNameProblem]. */
    fun rename(trackId: Long, name: String) {
        viewModelScope.launch { sessions.renameTrack(trackId, name) }
    }

    fun delete(trackId: Long) {
        viewModelScope.launch { sessions.deleteTrack(trackId) }
    }

    /** Lap timing runs for [trackId] from now on; null switches it off. */
    fun setActive(trackId: Long?) {
        viewModelScope.launch { sessions.setActiveTrack(trackId) }
    }

    companion object {
        fun forGraph(graph: AppGraph) = GpsTracksViewModel(
            location = graph.location,
            sessions = graph.lapSessions,
            gpsSettings = graph.gpsSettingsRepository,
            trips = graph.tripLibrary.trips,
            connection = graph.settingsRepository.connectionSettings,
        )
    }
}

/** The GPS mark of the bottom bar needs nothing but the status. */
internal class GpsMarkViewModel(location: LocationSource) : ViewModel() {
    val mark: StateFlow<GpsMark> = location.status.map(::gpsMark)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GpsMark(GpsMarkKind.SEARCHING, null))

    companion object {
        fun forGraph(graph: AppGraph) = GpsMarkViewModel(graph.location)
    }
}
