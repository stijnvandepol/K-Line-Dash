package nl.stijnvdpol.klinedash.ui

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.AppGraph
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.data.TripSettingsRepository
import nl.stijnvdpol.klinedash.service.CopyProgress
import nl.stijnvdpol.klinedash.service.DeleteResult
import nl.stijnvdpol.klinedash.service.ExportController
import nl.stijnvdpol.klinedash.service.ExportState
import nl.stijnvdpol.klinedash.service.ObdServiceState
import nl.stijnvdpol.klinedash.service.RecordingState
import nl.stijnvdpol.klinedash.service.TripLibrary
import nl.stijnvdpol.klinedash.service.TripRecorder

private const val TAG = "TripsViewModel"
private const val STOP_TIMEOUT_MS = 5_000L
private const val NOTICE_MS = 6_000L
private const val COPY_FAILED_NOTICE = "Copying stopped unexpectedly. Check the USB stick or SD card and try again."

/**
 * The TRIPS page: recording, the trip list, copy to a USB stick or SD card and the Wi-Fi export.
 * The recorder, the library and the export controller belong to the app (the service runs them);
 * this only starts and stops them and keeps what the page shows about the last copy and notices.
 */
class TripsViewModel(
    private val recorder: TripRecorder,
    private val library: TripLibrary,
    private val exporter: ExportController,
    private val tripSettings: TripSettingsRepository,
    connected: Flow<Boolean>,
) : ViewModel() {
    val recording: StateFlow<RecordingState> = recorder.state

    /** Why the last start or trip failed, until the next successful start. */
    val recordingError: StateFlow<String?> = recorder.lastError

    /** A trip can only be started while the car is connected (a disconnected link would record nothing). */
    val isConnected: StateFlow<Boolean> = connected.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** Null until the first read from the database. */
    val isAutoRecord: StateFlow<Boolean?> = tripSettings.settings.map { it.autoRecord }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Newest first; null until the first read, so the empty text does not flash up on every visit. */
    val trips: StateFlow<List<Trip>?> = library.trips
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val export: StateFlow<ExportState> = exporter.state

    private val _copy = MutableStateFlow<CopyProgress?>(null)

    /** Progress, then the result, of the last copy to USB or SD; null when none ran. */
    val copy: StateFlow<CopyProgress?> = _copy.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /** A short message for a few seconds: a refused start or delete, a missing folder picker. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var copyJob: Job? = null
    private var noticeJob: Job? = null

    fun startRecording() {
        viewModelScope.launch { startNotice(recorder.start())?.let(::showNotice) }
    }

    fun stopRecording() {
        viewModelScope.launch { recorder.stop() }
    }

    fun setAutoRecord(isOn: Boolean) {
        viewModelScope.launch { tripSettings.update { it.copy(autoRecord = isOn) } }
    }

    fun delete(tripId: Long) {
        viewModelScope.launch {
            when (val result = library.delete(tripId)) {
                is DeleteResult.Failed -> showNotice(result.reason)
                DeleteResult.IsRecording -> showNotice(STILL_RECORDING_NOTICE)
                // Gone already: the list updates by itself.
                DeleteResult.Deleted, DeleteResult.NotFound -> Unit
            }
        }
    }

    /** Copies every finished trip into the folder the user picked. */
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
                showNotice(COPY_FAILED_NOTICE)
            }
        }
    }

    /** The result line is about the last copy only; it goes when the page is left. */
    fun clearCopyResult() {
        if (_copy.value?.isFinished == true) _copy.value = null
    }

    fun folderPickerMissing() = showNotice(PICKER_MISSING_NOTICE)

    fun startExport() = exporter.start()

    fun stopExport() = exporter.stop()

    private fun showNotice(text: String) {
        _notice.value = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _notice.value = null
        }
    }

    companion object {
        fun forGraph(graph: AppGraph) = TripsViewModel(
            recorder = graph.tripRecorder,
            library = graph.tripLibrary,
            exporter = graph.exportController,
            tripSettings = graph.tripSettingsRepository,
            connected = ObdServiceState.connectionState.map { it is ConnectionState.Connected },
        )
    }
}
