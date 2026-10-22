package nl.stijnvdpol.klinedash.ui.wizard

import nl.stijnvdpol.klinedash.core.elm.AdapterInfo
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.elm.ECU_NOT_RESPONDING_PREFIX
import nl.stijnvdpol.klinedash.core.elm.FailureKind
import nl.stijnvdpol.klinedash.core.transport.LOCKED_ADAPTER_MESSAGE
import nl.stijnvdpol.klinedash.core.transport.TransportKind
import nl.stijnvdpol.klinedash.transport.ADAPTER_UNAVAILABLE_MESSAGE
import nl.stijnvdpol.klinedash.transport.BLUETOOTH_OFF_MESSAGE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkCheckTest {
    private val info = AdapterInfo("ELM327 v1.5", "3", "ISO 9141-2", 13.9, false, emptySet())
    private val facts = LinkFacts(TransportKind.BT_CLASSIC, hasPermission = true, hasAdapter = true)
    private val tracker = LinkCheckTracker()

    private fun reconnecting(reason: String) = ConnectionState.Reconnecting(attempt = 1, delayMs = 2_000, reason = reason)

    private fun failureOf(result: LinkCheck): LinkFailure = (result as LinkCheck.Failed).cause

    // --- following one attempt ------------------------------------------------------------------

    @Test
    fun `a link that was up before the attempt is not mistaken for its result`() {
        assertEquals(LinkCheck.Starting, tracker.onState(ConnectionState.Connected(info), null, facts))
        assertEquals(LinkCheck.Starting, tracker.onState(ConnectionState.Idle, null, facts))
    }

    @Test
    fun `connecting and initializing show their state until the car answers`() {
        val connecting = ConnectionState.Connecting("OBDII")
        val initializing = ConnectionState.Initializing("Resetting adapter")
        assertEquals(LinkCheck.Running(connecting), tracker.onState(connecting, null, facts))
        assertEquals(LinkCheck.Running(initializing), tracker.onState(initializing, null, facts))
        assertEquals(LinkCheck.Connected(info, null), tracker.onState(ConnectionState.Connected(info), null, facts))
    }

    @Test
    fun `the sensor count arrives after the connection and then keeps updating`() {
        tracker.onState(ConnectionState.Connecting("OBDII"), null, facts)
        tracker.onState(ConnectionState.Connected(info), null, facts)
        assertEquals(LinkCheck.Connected(info, 18), tracker.onState(ConnectionState.Connected(info), 18, facts))
    }

    @Test
    fun `a connection stays a success when the link drops later`() {
        tracker.onState(ConnectionState.Connecting("OBDII"), null, facts)
        tracker.onState(ConnectionState.Connected(info), 18, facts)
        assertEquals(LinkCheck.Connected(info, 18), tracker.onState(reconnecting("The connection to the adapter dropped"), 18, facts))
    }

    @Test
    fun `a failure stays after the link is stopped`() {
        tracker.onState(ConnectionState.Connecting("OBDII"), null, facts)
        val failed = tracker.onState(reconnecting("Cannot connect"), null, facts)
        assertEquals(failed, tracker.onState(ConnectionState.Idle, null, facts))
    }

    @Test
    fun `resetting starts a new attempt`() {
        tracker.onState(ConnectionState.Connecting("OBDII"), null, facts)
        tracker.onState(ConnectionState.Connected(info), null, facts)
        tracker.reset()
        assertEquals(LinkCheck.Starting, tracker.onState(ConnectionState.Connected(info), null, facts))
    }

    // --- what went wrong ------------------------------------------------------------------------

    private fun failAfterConnecting(reason: String, withFacts: LinkFacts = facts, reachedAdapter: Boolean = false): LinkCheck {
        tracker.onState(ConnectionState.Connecting("OBDII"), null, withFacts)
        if (reachedAdapter) tracker.onState(ConnectionState.Initializing("Resetting adapter"), null, withFacts)
        return tracker.onState(reconnecting(reason), null, withFacts)
    }

    @Test
    fun `an adapter that never came up is not reached`() {
        assertEquals(LinkFailure.ADAPTER_NOT_REACHED, failureOf(failAfterConnecting("Cannot connect to the adapter over Bluetooth.")))
    }

    @Test
    fun `a car that stays silent is an ECU that does not answer`() {
        val reason = "$ECU_NOT_RESPONDING_PREFIX (NO DATA). Is the ignition on?"
        assertEquals(LinkFailure.ECU_NOT_RESPONDING, failureOf(failAfterConnecting(reason, reachedAdapter = true)))
    }

    @Test
    fun `an adapter that came up and then went quiet stopped answering`() {
        val result = failAfterConnecting("Adapter stopped answering during setup (ATZ)", reachedAdapter = true)
        assertEquals(LinkFailure.ADAPTER_STOPPED_ANSWERING, failureOf(result))
    }

    @Test
    fun `an adapter that only talks to its own app is unsupported, also on a later attempt`() {
        tracker.onState(ConnectionState.Connecting("BlueDriver"), null, facts)
        val failed = ConnectionState.Failed(LOCKED_ADAPTER_MESSAGE, FailureKind.UNSUPPORTED_ADAPTER)
        assertEquals(LinkFailure.UNSUPPORTED_ADAPTER, failureOf(tracker.onState(failed, null, facts)))
        tracker.reset()
        assertEquals(LinkFailure.UNSUPPORTED_ADAPTER, failureOf(failAfterConnecting(LOCKED_ADAPTER_MESSAGE, reachedAdapter = true)))
    }

    @Test
    fun `missing Bluetooth permission is told apart from an adapter that is out of reach`() {
        val denied = facts.copy(hasPermission = false)
        val result = failAfterConnecting("Bluetooth permission is missing (BLUETOOTH_CONNECT).", denied)
        assertEquals(LinkFailure.PERMISSION_DENIED, failureOf(result))
    }

    @Test
    fun `Wi-Fi and the simulator never fail for a Bluetooth permission`() {
        val wifi = LinkFacts(TransportKind.WIFI, hasPermission = false, hasAdapter = true)
        assertEquals(LinkFailure.ADAPTER_NOT_REACHED, failureOf(failAfterConnecting("Connecting to 192.168.0.10 failed", wifi)))
    }

    @Test
    fun `nothing chosen is a failure with its own advice`() {
        val none = facts.copy(hasAdapter = false)
        tracker.onState(ConnectionState.Connecting(""), null, none)
        assertEquals(LinkFailure.NO_ADAPTER_CHOSEN, failureOf(tracker.onState(ConnectionState.Failed("No adapter chosen"), null, none)))
    }

    @Test
    fun `Bluetooth that is off or not offered to apps has its own advice`() {
        assertEquals(LinkFailure.BLUETOOTH_OFF, failureOf(failAfterConnecting(BLUETOOTH_OFF_MESSAGE)))
        tracker.reset()
        assertEquals(LinkFailure.BLUETOOTH_UNAVAILABLE, failureOf(failAfterConnecting(ADAPTER_UNAVAILABLE_MESSAGE)))
    }

    @Test
    fun `any other final failure is shown with its own words`() {
        tracker.onState(ConnectionState.Connecting("OBDII"), null, facts)
        val result = tracker.onState(ConnectionState.Failed("Something odd"), null, facts)
        assertEquals(LinkCheck.Failed(LinkFailure.OTHER, "Something odd"), result)
    }

    // --- the advice -----------------------------------------------------------------------------

    @Test
    fun `every cause has advice, and the ones that need a fix offer it`() {
        LinkFailure.entries.forEach { cause ->
            TransportKind.entries.forEach { kind -> linkAdvice(cause, kind) }
        }
        assertEquals(FixAction.ALLOW_BLUETOOTH, linkAdvice(LinkFailure.PERMISSION_DENIED, TransportKind.BLE).action)
        assertEquals(FixAction.OPEN_BLUETOOTH_SETTINGS, linkAdvice(LinkFailure.BLUETOOTH_OFF, TransportKind.BT_CLASSIC).action)
        assertEquals(FixAction.OPEN_WIFI_SETTINGS, linkAdvice(LinkFailure.ADAPTER_NOT_REACHED, TransportKind.WIFI).action)
        assertEquals(FixAction.OPEN_BLUETOOTH_SETTINGS, linkAdvice(LinkFailure.NO_ADAPTER_CHOSEN, TransportKind.BT_CLASSIC).action)
    }

    @Test
    fun `an unsupported adapter gets the list of adapters that work`() {
        assertTrue(linkAdvice(LinkFailure.UNSUPPORTED_ADAPTER, TransportKind.BLE).showsAdapterList)
        LinkFailure.entries.filter { it != LinkFailure.UNSUPPORTED_ADAPTER }.forEach {
            assertEquals("$it", false, linkAdvice(it, TransportKind.BLE).showsAdapterList)
        }
    }

    @Test
    fun `the advice for an adapter that was not reached depends on how it connects`() {
        val classic = linkAdvice(LinkFailure.ADAPTER_NOT_REACHED, TransportKind.BT_CLASSIC).body
        val ble = linkAdvice(LinkFailure.ADAPTER_NOT_REACHED, TransportKind.BLE).body
        val wifi = linkAdvice(LinkFailure.ADAPTER_NOT_REACHED, TransportKind.WIFI).body
        assertEquals(3, setOf(classic, ble, wifi).size)
    }
}
