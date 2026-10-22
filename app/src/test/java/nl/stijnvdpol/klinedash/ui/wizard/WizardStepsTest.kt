package nl.stijnvdpol.klinedash.ui.wizard

import nl.stijnvdpol.klinedash.core.elm.AdapterInfo
import nl.stijnvdpol.klinedash.core.transport.TransportKind
import nl.stijnvdpol.klinedash.data.ConnectionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardStepsTest {
    private val info = AdapterInfo("ELM327 v1.5", "3", "ISO 9141-2", 13.9, false, emptySet())

    @Test
    fun `a real adapter goes through all six steps in order`() {
        assertEquals(
            listOf(WizardStep.WELCOME, WizardStep.ADAPTER, WizardStep.CONNECT, WizardStep.TEST, WizardStep.PREFERENCES, WizardStep.DONE),
            wizardSteps(TransportKind.BT_CLASSIC),
        )
        assertEquals(wizardSteps(TransportKind.BT_CLASSIC), wizardSteps(TransportKind.BLE))
        assertEquals(wizardSteps(TransportKind.BT_CLASSIC), wizardSteps(TransportKind.WIFI))
    }

    @Test
    fun `the simulator has nothing to connect, so it skips that step`() {
        val steps = wizardSteps(TransportKind.SIMULATOR)
        assertFalse(WizardStep.CONNECT in steps)
        assertEquals(5, steps.size)
        assertEquals(WizardStep.TEST, nextStep(WizardStep.ADAPTER, TransportKind.SIMULATOR))
        assertEquals(WizardStep.ADAPTER, previousStep(WizardStep.TEST, TransportKind.SIMULATOR))
    }

    @Test
    fun `the position counts from one within the steps of the adapter`() {
        assertEquals(StepPosition(1, 6), stepPosition(WizardStep.WELCOME, TransportKind.BLE))
        assertEquals(StepPosition(4, 6), stepPosition(WizardStep.TEST, TransportKind.BLE))
        assertEquals(StepPosition(3, 5), stepPosition(WizardStep.TEST, TransportKind.SIMULATOR))
        assertEquals(StepPosition(5, 5), stepPosition(WizardStep.DONE, TransportKind.SIMULATOR))
    }

    @Test
    fun `the ends of the setup have no step beyond them`() {
        assertNull(previousStep(WizardStep.WELCOME, TransportKind.WIFI))
        assertNull(nextStep(WizardStep.DONE, TransportKind.WIFI))
        assertEquals(WizardStep.CONNECT, nextStep(WizardStep.ADAPTER, TransportKind.WIFI))
    }

    @Test
    fun `Bluetooth needs a chosen device of its own kind before the test`() {
        assertFalse(isAdapterChosen(ConnectionSettings(kind = TransportKind.BT_CLASSIC)))
        assertTrue(isAdapterChosen(ConnectionSettings(kind = TransportKind.BT_CLASSIC, btAddress = "AA:BB:CC")))
        assertFalse(isAdapterChosen(ConnectionSettings(kind = TransportKind.BLE, btAddress = "AA:BB:CC")))
        assertTrue(isAdapterChosen(ConnectionSettings(kind = TransportKind.BLE, bleAddress = "AA:BB:CC")))
    }

    @Test
    fun `Wi-Fi needs a host and a usable port`() {
        assertTrue(isAdapterChosen(ConnectionSettings(kind = TransportKind.WIFI)))
        assertFalse(isAdapterChosen(ConnectionSettings(kind = TransportKind.WIFI, wifiHost = "")))
        assertFalse(isAdapterChosen(ConnectionSettings(kind = TransportKind.WIFI, wifiPort = 0)))
        assertFalse(isAdapterChosen(ConnectionSettings(kind = TransportKind.WIFI, wifiPort = 70_000)))
    }

    @Test
    fun `Next waits for the adapter at Connect and for the connection at Test`() {
        val unchosen = ConnectionSettings(kind = TransportKind.BT_CLASSIC)
        val chosen = unchosen.copy(btAddress = "AA:BB:CC")
        assertFalse(canContinue(WizardStep.CONNECT, unchosen, LinkCheck.NotStarted))
        assertTrue(canContinue(WizardStep.CONNECT, chosen, LinkCheck.NotStarted))
        assertFalse(canContinue(WizardStep.TEST, chosen, LinkCheck.Starting))
        assertFalse(canContinue(WizardStep.TEST, chosen, LinkCheck.Failed(LinkFailure.OTHER, "x")))
        assertTrue(canContinue(WizardStep.TEST, chosen, LinkCheck.Connected(info, sensorCount = null)))
    }

    @Test
    fun `the other steps never block`() {
        val settings = ConnectionSettings()
        listOf(WizardStep.WELCOME, WizardStep.ADAPTER, WizardStep.PREFERENCES, WizardStep.DONE).forEach {
            assertTrue("$it", canContinue(it, settings, LinkCheck.NotStarted))
        }
    }
}
