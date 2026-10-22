package nl.stijnvdpol.klinedash.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.AppGraph
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.data.Track
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.service.GateResult
import nl.stijnvdpol.klinedash.service.GpsStatus
import nl.stijnvdpol.klinedash.service.LapSessions
import nl.stijnvdpol.klinedash.service.LiveTripState
import nl.stijnvdpol.klinedash.service.LocationSource
import nl.stijnvdpol.klinedash.service.ObdServiceState
import nl.stijnvdpol.klinedash.service.RecordingState
import nl.stijnvdpol.klinedash.service.StartResult
import nl.stijnvdpol.klinedash.service.TripRecorder
import nl.stijnvdpol.klinedash.ui.TimedMessage
import nl.stijnvdpol.klinedash.ui.startNotice

private const val STOP_TIMEOUT_MS = 5_000L

/**
 * The session panel of the TRIPS page: starting and stopping a trip or a lap session, the live
 * statistics and lap timer, and setting the start/finish and sector lines. It follows the recorder
 * and the lap sessions of the app; the only thing it keeps itself is whether the user started a lap
 * session (so "Set start/finish here" shows before the track exists) and the track picked for the next one.
 * The track of a lap session is the active track while the session runs; the recorder releases it
 * when the trip ends (see TripRecorder), whether or not this screen exists.
 */
class SessionViewModel(
    private val recorder: TripRecorder,
    private val sessions: LapSessions,
    private val location: LocationSource,
    trips: Flow<List<Trip>>,
    connected: Flow<Boolean>,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    val recording: StateFlow<RecordingState> = recorder.state

    /** Why the last start or trip failed, until the next successful start. */
    val recordingError: StateFlow<String?> = recorder.lastError

    val live: StateFlow<LiveTripState> = recorder.live

    private val whileObserved = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS)

    /** The track lap timing runs for now. */
    val activeTrack: StateFlow<Track?> = sessions.activeTrack.stateIn(viewModelScope, whileObserved, null)

    private val pickedTrackId = MutableStateFlow<Long?>(null)

    /** The track "Start lap session" will use: the one picked, else the last used one; null: "Choose a track". */
    val chosenTrack: StateFlow<Track?> = combine(pickedTrackId, sessions.activeTrack, sessions.allTracks, trips) { picked, active, tracks, recent ->
        picked?.let { id -> tracks.firstOrNull { it.id == id } } ?: lastUsedTrack(active, recent, tracks)
    }.stateIn(viewModelScope, whileObserved, null)

    val tracks: StateFlow<List<Track>> = sessions.allTracks.stateIn(viewModelScope, whileObserved, emptyList())

    private val isLapMode = MutableStateFlow(false)

    /** True while the running trip is a lap session: started as one, or timed on a track since it started. */
    val isLapSession: StateFlow<Boolean> = combine(isLapMode, live) { isMode, state -> isMode || state.trackId != null }
        .distinctUntilChanged()
        .stateIn(viewModelScope, whileObserved, false)

    /** A trip has something to record with an adapter link or a GPS fix. */
    val canStart: StateFlow<Boolean> = combine(connected, location.status) { isConnected, status -> isConnected || status is GpsStatus.Fix }
        .stateIn(viewModelScope, whileObserved, false)

    /** Why the lines cannot be set now, null when they can. */
    val gateBlock: StateFlow<String?> = combine(location.status, location.fix) { status, fix -> gateBlockReason(status, fix) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, whileObserved, null)

    private val notice = TimedMessage(viewModelScope)

    /** The answer to the last tap on a start or gate button, for a few seconds. */
    val message: StateFlow<String?> = notice.text

    init {
        viewModelScope.launch {
            // A lap session ends with its trip; the recorder has released the track by then.
            recorder.state.collect { state -> if (state is RecordingState.Idle) isLapMode.value = false }
        }
    }

    /** A trip; it is timed on the active track when one is set (under GPS & Tracks), else it is a plain trip. */
    fun startTrip() {
        viewModelScope.launch { startRecording() }
    }

    /** A trip timed on [chosenTrack]; without one, the lines are set while driving. */
    fun startLapSession() {
        viewModelScope.launch {
            sessions.setActiveTrack(chosenTrack.value?.id)
            if (startRecording() is StartResult.Started) isLapMode.value = true
        }
    }

    /** Lap timing for a trip that is already recording (an automatic one). */
    fun startLapTiming() {
        isLapMode.value = true
    }

    fun stop() {
        viewModelScope.launch { recorder.stop() }
    }

    fun pickTrack(trackId: Long) {
        pickedTrackId.value = trackId
    }

    fun setStartFinishHere() {
        viewModelScope.launch {
            when (val result = sessions.startFinishHere(defaultTrackName(clock()))) {
                is GateResult.Placed -> {
                    pickedTrackId.value = null
                    notice.show(START_FINISH_SET)
                }
                is GateResult.Rejected -> notice.show(result.reason)
            }
        }
    }

    fun addSectorHere() {
        viewModelScope.launch {
            val trackId = live.value.trackId ?: activeTrack.value?.id
            if (trackId == null) {
                notice.show(NO_TRACK)
                return@launch
            }
            when (val result = sessions.addSectorHere(trackId)) {
                is GateResult.Placed -> notice.show(SECTOR_SET)
                is GateResult.Rejected -> notice.show(result.reason)
            }
        }
    }

    private suspend fun startRecording(): StartResult {
        val result = recorder.start()
        startNotice(result)?.let(notice::show)
        return result
    }

    companion object {
        fun forGraph(graph: AppGraph) = SessionViewModel(
            recorder = graph.tripRecorder,
            sessions = graph.lapSessions,
            location = graph.location,
            trips = graph.tripLibrary.trips,
            connected = ObdServiceState.connectionState.map { it is ConnectionState.Connected },
        )
    }
}
