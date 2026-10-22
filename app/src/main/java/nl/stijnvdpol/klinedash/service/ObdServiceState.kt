package nl.stijnvdpol.klinedash.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import nl.stijnvdpol.klinedash.core.alert.Alert
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.elm.ElmLink
import nl.stijnvdpol.klinedash.core.obd.ObdPoller
import nl.stijnvdpol.klinedash.core.sim.SimulatorTransport

/**
 * How the UI reaches the link owned by [ObdService].
 *
 * Service and UI live in the same process, so a process-wide singleton is simpler and more
 * robust than a bound service: no ServiceConnection lifecycle, and the UI can come and go while
 * the service keeps the link. If the process dies, both die and [link] is null again.
 */
object ObdServiceState {
    private val _link = MutableStateFlow<ElmLink?>(null)
    val link: StateFlow<ElmLink?> = _link.asStateFlow()

    private val _poller = MutableStateFlow<ObdPoller?>(null)

    /** The poller that belongs to [link]; screens register the signals they show with it. */
    val poller: StateFlow<ObdPoller?> = _poller.asStateFlow()

    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())

    /** Warnings that are active right now (coolant, battery, MIL, ...); empty while there is no link. */
    val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()

    /** State of the current link, or Idle while there is none. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val connectionState: Flow<ConnectionState> =
        link.flatMapLatest { it?.state ?: flowOf<ConnectionState>(ConnectionState.Idle) }

    private val _isAppVisible = MutableStateFlow(false)

    /** True while the app has a visible activity; see [setAppVisible]. */
    val isAppVisible: StateFlow<Boolean> = _isAppVisible.asStateFlow()

    private val _locationPermissionChanges = MutableStateFlow(0)

    /** Counts [notifyLocationPermissionChanged] calls; the service re-checks the permission on every change. */
    val locationPermissionChanges: StateFlow<Int> = _locationPermissionChanges.asStateFlow()

    /**
     * The UI calls this from MainActivity.onStart (true) and onStop (false). It is what lets the
     * service add the `location` foreground service type: Android only allows that while the app is
     * visible, and the service is started at boot as `connectedDevice` only. With true (and the fine
     * location permission) the service upgrades itself and starts GPS; with false GPS keeps running
     * while a trip records and stops [HIDDEN_GPS_GRACE_MS] after the app is hidden otherwise.
     */
    fun setAppVisible(isVisible: Boolean) {
        _isAppVisible.value = isVisible
    }

    /** The UI calls this after the user answered the location permission dialog (granted or not). */
    fun notifyLocationPermissionChanged() {
        _locationPermissionChanges.update { it + 1 }
    }

    private val _simulatorEngineRunning = MutableStateFlow(true)
    val simulatorEngineRunning: StateFlow<Boolean> = _simulatorEngineRunning.asStateFlow()

    @Volatile
    private var simulator: SimulatorTransport? = null

    fun setSimulatorEngineRunning(running: Boolean) {
        _simulatorEngineRunning.value = running
        simulator?.setEngineRunning(running)
    }

    /** Called by the transport factory: each reconnect creates a new simulator, which must follow the toggle. */
    internal fun attachSimulator(transport: SimulatorTransport) {
        simulator = transport
        transport.setEngineRunning(_simulatorEngineRunning.value)
    }

    internal fun publishAlerts(active: List<Alert>) {
        _alerts.value = active
    }

    internal fun publish(link: ElmLink, poller: ObdPoller) {
        _link.value = link
        _poller.value = poller
    }

    internal fun unpublish(link: ElmLink) {
        if (_link.compareAndSet(link, null)) {
            _poller.value = null
            _alerts.value = emptyList()
        }
    }
}
