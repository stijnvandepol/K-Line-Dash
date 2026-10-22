package nl.stijnvdpol.klinedash.ui

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.AppGraph
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.data.TripSettings
import nl.stijnvdpol.klinedash.data.TripSettingsRepository
import nl.stijnvdpol.klinedash.service.CopyProgress
import nl.stijnvdpol.klinedash.service.DeleteResult
import nl.stijnvdpol.klinedash.service.ExportController
import nl.stijnvdpol.klinedash.service.ExportState
import nl.stijnvdpol.klinedash.service.RecordingState
import nl.stijnvdpol.klinedash.service.TripLibrary

private const val TAG = "TripsViewModel"
private const val STOP_TIMEOUT_MS = 5_000L
private const val COPY_FAILED_NOTICE = "Copying stopped unexpectedly. Check the USB stick or SD card and try again."

/**
 * The TRIPS page apart from the session panel (see SessionViewModel): the trip list, the automatic
 * recording switches, copy to a USB stick or SD card and the Wi-Fi export. The recorder, the library
 * and the export controller belong to the app (the service runs them); this only starts and stops
 * them and keeps what the page shows about the last copy and notices.
 */
class TripsViewModel(
    val recording: StateFlow<RecordingState>,
    private val library: TripLibrary,
    private val exporter: ExportController,
    private val tripSettings: TripSettingsRepository,
    trackNames: Flow<Map<Long, String>>,
) : ViewModel() {
    /** Null until the first read from the database. */
    val settings: StateFlow<TripSettings?> = tripSettings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Newest first; null until the first read, so the empty text does not flash up on every visit. */
    val trips: StateFlow<List<Trip>?> = library.trips
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Track id to name, for the lap summary of the list rows. */
    val trackNames: StateFlow<Map<Long, String>> = trackNames
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    val export: StateFlow<ExportState> = exporter.state

    private val _copy = MutableStateFlow<CopyProgress?>(null)

    /** Progress, then the result, of the last copy to USB or SD; null when none ran. */
    val copy: StateFlow<CopyProgress?> = _copy.asStateFlow()

    private val message = TimedMessage(viewModelScope)

    /** A short message for a few seconds: a refused delete, a missing folder picker. */
    val notice: StateFlow<String?> = message.text

    private var copyJob: Job? = null

    fun setAutoRecord(isOn: Boolean) {
        viewModelScope.launch { tripSettings.update { it.copy(autoRecord = isOn) } }
    }

    fun setAutoStartWhenMoving(isOn: Boolean) {
        viewModelScope.launch { tripSettings.update { it.copy(autoStartWhenMoving = isOn) } }
    }

    fun delete(tripId: Long) {
        viewModelScope.launch {
            when (val result = library.delete(tripId)) {
                is DeleteResult.Failed -> message.show(result.reason)
                DeleteResult.IsRecording -> message.show(STILL_RECORDING_NOTICE)
                // Gone already: the list updates by itself.
                DeleteResult.Deleted, DeleteResult.NotFound -> Unit
            }
        }
    }

    /** Copies every finished trip, with the PC files exported for it, into the folder the user picked. */
    fun copyTo(treeUri: Uri) {
        copyJob?.cancel()
        copyJob = viewModelScope.launch {
            try {
                library.copyToTree(treeUri).collect { _copy.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A folder the system no longer grants access to, or an unexpected provider error.
                Log.e(TAG, "Copying the trips failed", e)
                _copy.value = null
                message.show(COPY_FAILED_NOTICE)
            }
        }
    }

    /** The result line is about the last copy only; it goes when the page is left. */
    fun clearCopyResult() {
        if (_copy.value?.isFinished == true) _copy.value = null
    }

    fun folderPickerMissing() = message.show(PICKER_MISSING_NOTICE)

    fun startExport() = exporter.start()

    fun stopExport() = exporter.stop()

    companion object {
        fun forGraph(graph: AppGraph) = TripsViewModel(
            recording = graph.tripRecorder.state,
            library = graph.tripLibrary,
            exporter = graph.exportController,
            tripSettings = graph.tripSettingsRepository,
            trackNames = graph.lapSessions.allTracks.map { tracks -> tracks.associate { it.id to it.name } },
        )
    }
}
