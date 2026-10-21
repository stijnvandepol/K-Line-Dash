package nl.stijnvdpol.klinedash.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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

    /** State of the current link, or Idle while there is none. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val connectionState: Flow<ConnectionState> =
        link.flatMapLatest { it?.state ?: flowOf<ConnectionState>(ConnectionState.Idle) }

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

    internal fun publish(link: ElmLink, poller: ObdPoller) {
        _link.value = link
        _poller.value = poller
    }

    internal fun unpublish(link: ElmLink) {
        if (_link.compareAndSet(link, null)) _poller.value = null
    }
}
