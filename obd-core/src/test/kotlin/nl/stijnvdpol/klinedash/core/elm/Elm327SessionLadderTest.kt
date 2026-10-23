package nl.stijnvdpol.klinedash.core.elm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.assertFails
import nl.stijnvdpol.klinedash.core.car.CarMake
import nl.stijnvdpol.klinedash.core.log.LogKind
import nl.stijnvdpol.klinedash.core.log.RingCommLog
import nl.stijnvdpol.klinedash.core.sim.SimulatorConfig
import nl.stijnvdpol.klinedash.core.sim.SimulatorTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The explicit protocol ladder of [Elm327Session] against the simulator: ATSP0 fails, a fixed protocol works. */
@OptIn(ExperimentalCoroutinesApi::class)
class Elm327SessionLadderTest {

    private class Rig(val config: SimulatorConfig) {
        val sim = SimulatorTransport(config)
        val log = RingCommLog()
        val session = Elm327Session(sim, log)
        val steps = mutableListOf<String>()

        fun sent(): List<String> = log.snapshot().filter { it.kind == LogKind.TX }.map { it.text }

        /** The ATSPx selections after the ATSP0 search, in the order they were tried. */
        fun triedProtocols(): List<String> = sent().filter { it.startsWith("ATSP") && it != "ATSP0" }
    }

    private fun carOn(protocol: String) = SimulatorConfig().apply {
        autoDetectFails = true
        carProtocol = protocol
    }

    @Test
    fun `can car whose auto search fails connects through the can rung`() = runTest {
        val rig = Rig(carOn("6"))
        rig.sim.connect()
        val info = rig.session.initialize(null) { rig.steps.add(it) }

        assertEquals("6", info.protocolNumber)
        assertTrue(info.isCan)
        assertFalse(info.usedSavedProtocol)
        assertEquals(listOf("ATSP3", "ATSP5", "ATSP4", "ATSPA4", "ATSP6"), rig.triedProtocols())
        assertTrue("Trying CAN 11-bit 500 kbaud" in rig.steps)
    }

    @Test
    fun `29-bit can car is found on the second can rung`() = runTest {
        val rig = Rig(carOn("7"))
        rig.sim.connect()
        val info = rig.session.initialize(null) {}
        assertEquals("7", info.protocolNumber)
        assertEquals(listOf("ATSP3", "ATSP5", "ATSP4", "ATSPA4", "ATSP6", "ATSP7"), rig.triedProtocols())
    }

    @Test
    fun `j1850 pwm car whose auto search fails connects through atsp1`() = runTest {
        val rig = Rig(carOn("1"))
        rig.sim.connect()
        val info = rig.session.initialize(null) { rig.steps.add(it) }

        assertEquals("1", info.protocolNumber)
        assertFalse(info.isCan)
        assertEquals("SAE J1850 PWM", info.protocolName)
        assertEquals("ATSP1", rig.triedProtocols().last())
        assertTrue("Trying J1850 PWM" in rig.steps)
    }

    @Test
    fun `j1850 vpw car is the very last rung for an unknown make`() = runTest {
        val rig = Rig(carOn("2"))
        rig.sim.connect()
        val info = rig.session.initialize(null) {}
        assertEquals("2", info.protocolNumber)
        assertEquals(
            listOf("ATSP3", "ATSP5", "ATSP4", "ATSPA4", "ATSP6", "ATSP7", "ATSP8", "ATSP9", "ATSP1", "ATSP2"),
            rig.triedProtocols(),
        )
    }

    @Test
    fun `k-line car of unknown make connects with the same steps as before`() = runTest {
        val rig = Rig(carOn("3"))
        rig.sim.connect()
        val info = rig.session.initialize(null) { rig.steps.add(it) }

        assertEquals("3", info.protocolNumber)
        assertEquals(
            listOf("Detecting protocol (ECU init takes a few seconds)", "Trying ISO 9141-2"),
            rig.steps.filter { it.startsWith("Detecting") || it.startsWith("Trying") },
        )
        val sent = rig.sent()
        assertEquals(
            listOf("ATSP0", "0100", "ATSP3", "0100", "ATDP", "ATRV"),
            sent.subList(sent.indexOf("ATSP0"), sent.size),
        )
    }

    @Test
    fun `make hint reorders the ladder`() = runTest {
        val rig = Rig(carOn("5"))
        rig.sim.connect()
        val info = rig.session.initialize(null, CarMake.RENAULT_DACIA) { rig.steps.add(it) }

        assertEquals("5", info.protocolNumber)
        assertEquals(listOf("ATSP5"), rig.triedProtocols())
        assertTrue("Trying KWP2000 (fast init)" in rig.steps)
    }

    @Test
    fun `ford hint finds a can car on the first rung and a pwm car on the second`() = runTest {
        val can = Rig(carOn("6"))
        can.sim.connect()
        can.session.initialize(null, CarMake.FORD) {}
        assertEquals(listOf("ATSP6"), can.triedProtocols())

        val pwm = Rig(carOn("1"))
        pwm.sim.connect()
        pwm.session.initialize(null, CarMake.FORD) {}
        assertEquals(listOf("ATSP6", "ATSP1"), pwm.triedProtocols())
    }

    @Test
    fun `vag never sends a j1850 selection and a silent ecu still ends in ecu not responding`() = runTest {
        val rig = Rig(SimulatorConfig().apply { initFails = true })
        rig.sim.connect()
        assertFails<EcuNotRespondingException> { rig.session.initialize(null, CarMake.VW_GROUP) {} }

        assertEquals(
            listOf("ATSP3", "ATSP4", "ATSP5", "ATSP6", "ATSP7", "ATSP8", "ATSP9"),
            rig.triedProtocols(),
        )
    }

    @Test
    fun `saved protocol is not tried again on the ladder`() = runTest {
        val rig = Rig(SimulatorConfig().apply { initFails = true })
        rig.sim.connect()
        assertFails<EcuNotRespondingException> { rig.session.initialize("6", CarMake.MAZDA) {} }

        // Once as the saved protocol, never again as a rung.
        assertEquals(1, rig.sent().count { it == "ATSP6" })
    }

    @Test
    fun `hanging adapter makes the whole ladder take the sum of the rung caps`() = runTest {
        val config = SimulatorConfig().apply {
            initFails = true
            busInitMs = 120_000 // longer than every timeout: each probe runs into its cap
        }
        val rig = Rig(config)
        rig.sim.connect()
        // A make without the Toyota profile: the simulator rejects its ATSPA4 and skips that probe.
        val make = CarMake.NISSAN_INFINITI
        assertFails<EcuNotRespondingException> { rig.session.initialize(null, make) {} }

        val ladderMs = fallbackOrder(make).sumOf { pauseBeforeRungMs(it) + rungTimeoutMs(it) }
        val detectMs = 25_000L
        // Setup commands and resync waits are tens of milliseconds each in the simulator.
        assertTrue("took ${currentTime}ms", currentTime in (detectMs + ladderMs)..(detectMs + ladderMs + 5_000))
    }

    @Test
    fun `worst case ladder for an unknown make adds 38 seconds to the old 46`() {
        val rungs = fallbackOrder(null)
        val kLineMs = rungs.filter { isKLineRung(it) }.sumOf { pauseBeforeRungMs(it) + rungTimeoutMs(it) }
        val busMs = rungs.filterNot { isKLineRung(it) }.sumOf { pauseBeforeRungMs(it) + rungTimeoutMs(it) }
        assertEquals(46_000L, kLineMs)
        assertEquals(37_800L, busMs)
    }
}
