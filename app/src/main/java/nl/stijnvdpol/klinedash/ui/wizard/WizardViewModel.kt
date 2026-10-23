package nl.stijnvdpol.klinedash.ui.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.AppGraph
import nl.stijnvdpol.klinedash.core.transport.TransportKind
import nl.stijnvdpol.klinedash.data.ConnectionSettings
import nl.stijnvdpol.klinedash.data.GeneralSettingsRepository
import nl.stijnvdpol.klinedash.data.GpsSettingsRepository
import nl.stijnvdpol.klinedash.data.TripSettingsRepository
import nl.stijnvdpol.klinedash.service.ObdServiceState
import nl.stijnvdpol.klinedash.transport.hasRequiredPermissions
import nl.stijnvdpol.klinedash.ui.pollerFlow
import nl.stijnvdpol.klinedash.ui.supportedSignals

/**
 * The first-run setup: which step it is on, the result of the connection test, and the choices
 * that have no screen of their own elsewhere (finishing, recording trips, the simulated GPS that
 * goes with the simulator). Connecting and choosing the adapter reuse ConnectionViewModel, the
 * theme and units reuse DisplayViewModel and the red line reuses CarViewModel, so the setup and
 * SETUP can never disagree.
 *
 * @param hasPermissions whether the permissions the chosen adapter needs are granted.
 */
internal class WizardViewModel(
    private val hasPermissions: () -> Boolean,
    private val connectionSettings: Flow<ConnectionSettings>,
    private val general: GeneralSettingsRepository,
    private val tripSettings: TripSettingsRepository,
    private val gpsSettings: GpsSettingsRepository,
) : ViewModel() {
    private val _step = MutableStateFlow(WizardStep.WELCOME)
    val step: StateFlow<WizardStep> = _step.asStateFlow()

    private val _check = MutableStateFlow<LinkCheck>(LinkCheck.NotStarted)

    /** The result of the connection test; [LinkCheck.NotStarted] until the Test step begins it. */
    val check: StateFlow<LinkCheck> = _check.asStateFlow()

    private val tracker = LinkCheckTracker()
    private var checkJob: Job? = null

    /** Whether recording trips by themselves is on. Null until read. */
    val isRecordingTrips: StateFlow<Boolean?> = tripSettings.settings
        .map { it.autoRecord }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Back to the first screen, for a setup that starts again from SETUP > Help. */
    fun restart() {
        resetCheck()
        _step.value = WizardStep.WELCOME
    }

    fun goTo(step: WizardStep) {
        // Coming back to the adapter or earlier means something may change, so the test starts over.
        if (step.ordinal < WizardStep.TEST.ordinal) resetCheck()
        _step.value = step
    }

    /**
     * Starts the connection test: [connect] starts the link (it may first ask for permissions) and
     * [stop] ends it, which is done once a failure is known so the app does not keep retrying in
     * the background while the user reads what to fix.
     */
    fun startCheck(connect: () -> Unit, stop: () -> Unit) {
        checkJob?.cancel()
        tracker.reset()
        _check.value = LinkCheck.Starting
        checkJob = viewModelScope.launch {
            val sensorCount = pollerFlow<Int?>(null) { poller -> poller.supportedPids.map { it?.let(::supportedSignals)?.size } }
            combine(ObdServiceState.connectionState, sensorCount, connectionSettings) { state, sensors, settings ->
                tracker.onState(state, sensors, LinkFacts(settings.kind, hasPermissions(), settings.hasAdapter))
            }.collect { result ->
                val isNewFailure = result is LinkCheck.Failed && _check.value !is LinkCheck.Failed
                _check.value = result
                if (isNewFailure) stop()
            }
        }
        connect()
    }

    /** Forgets the test (and stops following the link), for a step before it. */
    fun resetCheck() {
        checkJob?.cancel()
        checkJob = null
        tracker.reset()
        _check.value = LinkCheck.NotStarted
    }

    /**
     * The simulator drives the simulated GPS circuit too, so laps work for someone trying the app at
     * home; with a real adapter the car's own position is wanted, so the circuit goes off again.
     */
    fun onAdapterChosen(kind: TransportKind) {
        viewModelScope.launch { gpsSettings.update { it.copy(useSimulatedGps = kind == TransportKind.SIMULATOR) } }
    }

    fun setRecordTrips(isOn: Boolean) {
        viewModelScope.launch { tripSettings.update { it.copy(autoRecord = isOn) } }
    }

    /** Remembers that the setup was seen, so it does not come back by itself; also for "Skip setup". */
    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            general.update { it.copy(setupCompleted = true) }
            onDone()
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun forGraph(graph: AppGraph) = WizardViewModel(
            hasPermissions = { hasRequiredPermissions(graph.appContext) },
            connectionSettings = graph.settingsRepository.connectionSettings,
            general = graph.generalSettingsRepository,
            tripSettings = graph.tripSettingsRepository,
            gpsSettings = graph.gpsSettingsRepository,
        )
    }
}
