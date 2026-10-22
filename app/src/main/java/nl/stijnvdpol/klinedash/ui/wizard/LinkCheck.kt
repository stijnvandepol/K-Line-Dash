package nl.stijnvdpol.klinedash.ui.wizard

import nl.stijnvdpol.klinedash.core.elm.AdapterInfo
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.elm.ECU_NOT_RESPONDING_PREFIX
import nl.stijnvdpol.klinedash.core.elm.FailureKind
import nl.stijnvdpol.klinedash.core.transport.LOCKED_ADAPTER_MESSAGE
import nl.stijnvdpol.klinedash.core.transport.TransportKind
import nl.stijnvdpol.klinedash.transport.ADAPTER_UNAVAILABLE_MESSAGE
import nl.stijnvdpol.klinedash.transport.BLUETOOTH_OFF_MESSAGE
import nl.stijnvdpol.klinedash.transport.needsBluetoothPermissions

/*
 * The Test step of the setup: turns the stream of connection states of one connection attempt into
 * a result the screen can draw (connecting, connected, or failed with a cause that has a specific
 * fix). The connection manager retries forever by design, so the first reconnect of an attempt counts
 * as the failure here and the screen stops the link; the user then fixes the cause and retries.
 */

/** Why a connection attempt failed, in terms of what the user can do about it. */
internal enum class LinkFailure {
    /** The adapter answers, but not to standard OBD commands: it only works with its own app. */
    UNSUPPORTED_ADAPTER,

    /** The adapter works, the car does not answer: the ignition is off, or the wrong port. */
    ECU_NOT_RESPONDING,
    PERMISSION_DENIED,

    /** Nothing is chosen to connect to (not paired, not scanned). */
    NO_ADAPTER_CHOSEN,
    BLUETOOTH_OFF,

    /** The head unit does not let apps use its Bluetooth. */
    BLUETOOTH_UNAVAILABLE,

    /** The link to the adapter itself never came up: not plugged in, not paired, out of range, wrong Wi-Fi. */
    ADAPTER_NOT_REACHED,

    /** The adapter came up, then went silent or kept resetting. */
    ADAPTER_STOPPED_ANSWERING,
    OTHER,
}

/** What is known about the setup besides the state itself. */
internal data class LinkFacts(val kind: TransportKind, val hasPermission: Boolean, val hasAdapter: Boolean)

/** The result of one connection attempt. */
internal sealed interface LinkCheck {
    /** No attempt yet. */
    data object NotStarted : LinkCheck

    /** The attempt was started; the service has not reported its first state yet. */
    data object Starting : LinkCheck

    /** In progress: the state to show (connecting, initializing). */
    data class Running(val state: ConnectionState) : LinkCheck

    /** The adapter and the car answer. [sensorCount] is null while the car is still reporting which sensors it has. */
    data class Connected(val info: AdapterInfo, val sensorCount: Int?) : LinkCheck

    data class Failed(val cause: LinkFailure, val reason: String) : LinkCheck
}

/** What the failure was, from the final state and what is known about the setup. */
internal fun classifyFailure(state: ConnectionState, facts: LinkFacts, reachedAdapter: Boolean): LinkFailure {
    val reason = when (state) {
        is ConnectionState.Failed -> state.reason
        is ConnectionState.Reconnecting -> state.reason
        else -> ""
    }
    val isUnsupported = (state as? ConnectionState.Failed)?.kind == FailureKind.UNSUPPORTED_ADAPTER || reason == LOCKED_ADAPTER_MESSAGE
    return when {
        isUnsupported -> LinkFailure.UNSUPPORTED_ADAPTER
        facts.kind.needsBluetoothPermissions() && !facts.hasPermission -> LinkFailure.PERMISSION_DENIED
        !facts.hasAdapter -> LinkFailure.NO_ADAPTER_CHOSEN
        reason == BLUETOOTH_OFF_MESSAGE -> LinkFailure.BLUETOOTH_OFF
        reason == ADAPTER_UNAVAILABLE_MESSAGE -> LinkFailure.BLUETOOTH_UNAVAILABLE
        reason.startsWith(ECU_NOT_RESPONDING_PREFIX) -> LinkFailure.ECU_NOT_RESPONDING
        reachedAdapter -> LinkFailure.ADAPTER_STOPPED_ANSWERING
        state is ConnectionState.Reconnecting -> LinkFailure.ADAPTER_NOT_REACHED
        else -> LinkFailure.OTHER
    }
}

/**
 * Follows the states of one attempt. States from before the attempt (a link that was up earlier)
 * are ignored until the first Connecting shows that the new link has started; after that the first
 * Connected, Failed or Reconnecting decides, and the result stays.
 */
internal class LinkCheckTracker {
    private var hasStarted = false
    private var reachedAdapter = false
    private var failure: LinkCheck.Failed? = null
    private var connectedInfo: AdapterInfo? = null

    /** Call before a new attempt: forgets the previous one. */
    fun reset() {
        hasStarted = false
        reachedAdapter = false
        failure = null
        connectedInfo = null
    }

    fun onState(state: ConnectionState, sensorCount: Int?, facts: LinkFacts): LinkCheck {
        connectedInfo?.let { return LinkCheck.Connected(it, sensorCount) }
        failure?.let { return it }
        if (!hasStarted) {
            if (state !is ConnectionState.Connecting) return LinkCheck.Starting
            hasStarted = true
        }
        return when (state) {
            is ConnectionState.Connected -> LinkCheck.Connected(state.info, sensorCount).also { connectedInfo = state.info }
            is ConnectionState.Failed -> settle(state.reason, state, facts)
            is ConnectionState.Reconnecting -> settle(state.reason, state, facts)
            is ConnectionState.Initializing -> LinkCheck.Running(state).also { reachedAdapter = true }
            is ConnectionState.Connecting, ConnectionState.Idle -> LinkCheck.Running(state)
        }
    }

    private fun settle(reason: String, state: ConnectionState, facts: LinkFacts): LinkCheck =
        LinkCheck.Failed(classifyFailure(state, facts, reachedAdapter), reason).also { failure = it }
}
