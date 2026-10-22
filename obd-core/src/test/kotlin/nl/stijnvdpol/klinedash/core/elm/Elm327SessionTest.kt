package nl.stijnvdpol.klinedash.core.elm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.assertFails
import nl.stijnvdpol.klinedash.core.assertIs
import nl.stijnvdpol.klinedash.core.log.LogKind
import nl.stijnvdpol.klinedash.core.log.RingCommLog
import nl.stijnvdpol.klinedash.core.sim.SimulatorConfig
import nl.stijnvdpol.klinedash.core.sim.SimulatorTransport
import nl.stijnvdpol.klinedash.core.transport.LOCKED_ADAPTER_MESSAGE
import nl.stijnvdpol.klinedash.core.transport.TransportException
import nl.stijnvdpol.klinedash.core.transport.UnsupportedAdapterException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Elm327SessionTest {

    private class Rig(val config: SimulatorConfig = SimulatorConfig()) {
        val sim = SimulatorTransport(config)
        val log = RingCommLog()
        val session = Elm327Session(sim, log)
        val steps = mutableListOf<String>()

        fun sent(): List<String> = log.snapshot().filter { it.kind == LogKind.TX }.map { it.text }
    }

    @Test
    fun `auto detect path finds protocol 3 and reports adapter info`() = runTest {
        val rig = Rig()
        rig.sim.connect()
        val info = rig.session.initialize(null) { rig.steps.add(it) }

        assertEquals("ELM327 v1.5", info.elmVersion)
        assertEquals("3", info.protocolNumber)
        assertEquals("ISO 9141-2", info.protocolName)
        assertFalse(info.usedSavedProtocol)
        assertEquals(13.9, info.voltage!!, 0.001)
        assertTrue(info.unsupportedCommands.isEmpty())
        assertTrue(rig.steps.isNotEmpty())
        assertEquals(
            listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATI", "AT@1", "STI", "ATSP0", "0100", "ATDPN", "ATDP", "ATRV"),
            rig.sent(),
        )
    }

    @Test
    fun `engine off gives the lower battery voltage`() = runTest {
        val rig = Rig()
        rig.sim.setEngineRunning(false)
        rig.sim.connect()
        val info = rig.session.initialize(null) {}
        assertEquals(12.4, info.voltage!!, 0.001)
    }

    @Test
    fun `saved protocol skips detection`() = runTest {
        val rig = Rig()
        rig.sim.connect()
        val info = rig.session.initialize("3") { rig.steps.add(it) }

        assertTrue(info.usedSavedProtocol)
        assertEquals("3", info.protocolNumber)
        assertEquals("ISO 9141-2", info.protocolName)
        val sent = rig.sent()
        assertTrue("ATSP3" in sent)
        assertFalse("ATSP0" in sent)
        assertFalse("ATDPN" in sent)
    }

    @Test
    fun `saved protocol that fails falls back to detection`() = runTest {
        val rig = Rig()
        rig.sim.connect()
        val info = rig.session.initialize("5") {}

        assertFalse(info.usedSavedProtocol)
        assertEquals("3", info.protocolNumber)
        val sent = rig.sent()
        assertTrue("ATSP5" in sent)
        assertTrue("ATSP0" in sent)
        assertTrue(sent.indexOf("ATSP5") < sent.indexOf("ATSP0"))
    }

    @Test
    fun `clone rejecting atat1 and at at 1 is tolerated and recorded`() = runTest {
        val config = SimulatorConfig().apply { cloneRejectsAt.addAll(listOf("ATAT1", "AT@1")) }
        val rig = Rig(config)
        rig.sim.connect()
        val info = rig.session.initialize(null) {}

        assertEquals(setOf("ATAT1", "AT@1"), info.unsupportedCommands)
        assertEquals("3", info.protocolNumber)
    }

    @Test
    fun `unreadable atdpn after a successful search stays on auto detect`() = runTest {
        val config = SimulatorConfig().apply { cloneRejectsAt.add("ATDPN") }
        val rig = Rig(config)
        rig.sim.connect()
        val info = rig.session.initialize(null) {}

        assertEquals(Elm327Session.AUTO_PROTOCOL, info.protocolNumber)
        assertFalse(info.usedSavedProtocol)
        assertTrue(rig.log.snapshot().any { it.kind == LogKind.INFO && it.text.contains("ATDPN") })
    }

    @Test
    fun `adapter reset in the middle of init restarts the sequence once`() = runTest {
        val config = SimulatorConfig().apply { spontaneousResetAtCommand = 4 } // ATS0 gets a banner
        val rig = Rig(config)
        rig.sim.connect()
        val info = rig.session.initialize(null) { rig.steps.add(it) }

        assertEquals("3", info.protocolNumber)
        assertEquals(2, rig.sent().count { it == "ATZ" })
        assertTrue(rig.steps.any { it.contains("restarting", ignoreCase = true) })
    }

    @Test
    fun `adapter that keeps resetting gives up with a transport exception`() = runTest {
        val config = SimulatorConfig().apply { spontaneousResetRate = 1.0 }
        val rig = Rig(config)
        rig.sim.connect()
        val e = assertFails<TransportException> { rig.session.initialize(null) {} }
        assertTrue(e.message!!.contains("resetting"))
    }

    @Test
    fun `ecu that never answers throws ecu not responding`() = runTest {
        val config = SimulatorConfig().apply { initFails = true }
        val rig = Rig(config)
        rig.sim.connect()
        val e = assertFails<EcuNotRespondingException> { rig.session.initialize(null) {} }
        assertTrue(e.message!!.contains("ignition", ignoreCase = true))
    }

    @Test
    fun `ecu not responding also when a saved protocol was tried first`() = runTest {
        val config = SimulatorConfig().apply { initFails = true }
        val rig = Rig(config)
        rig.sim.connect()
        assertFails<EcuNotRespondingException> { rig.session.initialize("3") {} }
        val sent = rig.sent()
        assertTrue("ATSP3" in sent)
        assertTrue("ATSP0" in sent)
    }

    @Test
    fun `failed auto search falls back to explicit k-line protocols`() = runTest {
        val config = SimulatorConfig().apply {
            autoDetectFails = true
            carProtocol = "5"
        }
        val rig = Rig(config)
        rig.sim.connect()
        val info = rig.session.initialize(null) { rig.steps.add(it) }

        assertEquals("5", info.protocolNumber)
        assertFalse(info.usedSavedProtocol)
        val sent = rig.sent()
        // ISO 9141-2 is tried before KWP fast init, the Toyota profile is never reached.
        assertTrue(sent.indexOf("ATSP0") < sent.indexOf("ATSP3"))
        assertTrue(sent.indexOf("ATSP3") < sent.indexOf("ATSP5"))
        assertFalse("ATIIA13" in sent)
    }

    @Test
    fun `fallback ladder ends with the toyota profile and skips the saved protocol`() = runTest {
        val config = SimulatorConfig().apply { initFails = true }
        val rig = Rig(config)
        rig.sim.connect()
        assertFails<EcuNotRespondingException> { rig.session.initialize("3") {} }

        val sent = rig.sent()
        assertEquals(1, sent.count { it == "ATSP3" })
        assertTrue(listOf("ATIB96", "ATIIA13", "ATSH8113F1", "ATSW00").all { it in sent })
    }

    @Test
    fun `execute returns parsed data after init`() = runTest {
        val rig = Rig()
        rig.sim.connect()
        rig.session.initialize(null) {}

        val result = rig.session.execute("0100", 2_000)
        val data = assertIs<ElmResult.Data>(result)
        assertEquals(1, data.lines.size)
        assertTrue(data.lines[0].startsWith("4100"))
    }

    @Test
    fun `execute turns a missing prompt into timeout and logs an error`() = runTest {
        val rig = Rig()
        rig.sim.connect()
        rig.config.timeoutRate = 1.0

        val result = rig.session.execute("010C", 500)
        assertIs<ElmResult.Timeout>(result)
        assertTrue(rig.log.snapshot().any { it.kind == LogKind.ERROR })
    }

    @Test
    fun `execute rejects blank and control character commands without sending anything`() = runTest {
        val rig = Rig()
        rig.sim.connect()
        for (bad in listOf("", " ", "  ", "\r", "ATI\r", "01\n0C", "AT\tI", "0100\u0000")) {
            assertFails<IllegalArgumentException> { rig.session.execute(bad, 2_000) }
        }
        assertTrue(rig.sent().isEmpty())
    }

    @Test
    fun `timeout is followed by a resync space and the next command works`() = runTest {
        val rig = Rig()
        rig.sim.connect()
        rig.session.initialize(null) {}

        // A request takes ~450 ms, so a 100 ms timeout leaves the adapter busy.
        assertIs<ElmResult.Timeout>(rig.session.execute("010C", 100))
        val sent = rig.sent()
        assertEquals(" ", sent.last())
        assertEquals("010C", sent[sent.size - 2])
        assertTrue(rig.log.snapshot().any { it.kind == LogKind.RX && it.text.contains("STOPPED") })

        // Without the resync this command would have been swallowed as "STOPPED".
        val data = assertIs<ElmResult.Data>(rig.session.execute("010C", 2_000))
        assertTrue(data.lines.single().startsWith("410C"))
    }

    @Test
    fun `resync never sends a bare carriage return`() = runTest {
        val rig = Rig()
        rig.sim.connect()
        rig.session.initialize(null) {}
        rig.session.execute("010C", 100)

        val tx = rig.log.snapshot().filter { it.kind == LogKind.TX }.map { it.text }
        assertTrue(tx.none { it.isEmpty() || it.contains('\r') })
    }

    @Test
    fun `hung adapter costs only the resync wait and still returns the timeout`() = runTest {
        val rig = Rig()
        rig.sim.connect()
        rig.config.timeoutRate = 1.0

        val before = currentTime
        assertIs<ElmResult.Timeout>(rig.session.execute("010C", 500))
        // 500 ms for the command plus the 1.5 s resync wait, nothing more.
        assertEquals(2_000L, currentTime - before)
    }

    @Test
    fun `execute logs tx and rx`() = runTest {
        val rig = Rig()
        rig.sim.connect()
        rig.session.execute("ATI", 2_000)
        val entries = rig.log.snapshot()
        assertEquals(LogKind.TX, entries[0].kind)
        assertEquals("ATI", entries[0].text)
        assertEquals(LogKind.RX, entries[1].kind)
        assertTrue(entries[1].text.contains("ELM327"))
    }

    @Test
    fun `execute lets a dropped link propagate`() = runTest {
        val config = SimulatorConfig().apply { dropConnectionAfterCommands = 1 }
        val rig = Rig(config)
        rig.sim.connect()
        rig.session.execute("ATI", 2_000)
        assertFails<TransportException> { rig.session.execute("ATI", 2_000) }
    }

    @Test
    fun `adapter that never answers atz is reported as locked after two attempts`() = runTest {
        val rig = Rig()
        rig.sim.connect()
        rig.config.timeoutRate = 1.0
        val e = assertFails<UnsupportedAdapterException> { rig.session.initialize(null) {} }
        assertEquals(LOCKED_ADAPTER_MESSAGE, e.message)
        assertEquals(2, rig.sent().count { it == "ATZ" })
        assertFalse("ATE0" in rig.sent())
    }

    @Test
    fun `link lost after a good atz is an ordinary transport error`() = runTest {
        val rig = Rig(SimulatorConfig().apply { dropConnectionAfterCommands = 2 })
        rig.sim.connect()
        val e = assertFails<TransportException> { rig.session.initialize(null) {} }
        assertFalse(e is UnsupportedAdapterException)
    }

    // ---- adapter fingerprint ----

    @Test
    fun `plain clone is fingerprinted and keeps adaptive timing 1 even on can`() = runTest {
        val rig = Rig(SimulatorConfig().apply { carProtocol = "6" })
        rig.sim.connect()
        val info = rig.session.initialize(null) {}

        assertEquals(ChipFamily.CLONE, info.profile.family)
        assertEquals("ELM327 v1.5", info.profile.firmware)
        assertEquals("OBDII to RS232 Interpreter", info.profile.description)
        assertNull(info.profile.stnId)
        assertFalse(info.profile.usesAdaptiveTiming2)
        assertFalse("ATAT2" in rig.sent())
        assertTrue(info.isCan)
        assertTrue(info.unsupportedCommands.isEmpty())
    }

    @Test
    fun `stn chip is recognised by sti and gets adaptive timing 2 on can`() = runTest {
        val config = SimulatorConfig().apply {
            carProtocol = "6"
            elmVersion = "ELM327 v1.3a"
            stnDeviceId = "STN1110 v4.2.0"
        }
        val rig = Rig(config)
        rig.sim.connect()
        val info = rig.session.initialize(null) {}

        assertEquals(ChipFamily.STN, info.profile.family)
        assertEquals("STN1110 v4.2.0", info.profile.stnId)
        assertTrue(info.profile.usesAdaptiveTiming2)
        assertEquals("ATAT2", rig.sent().last())
        assertFalse("STI" in info.unsupportedCommands)
    }

    @Test
    fun `adaptive timing 2 is never used on k-line`() = runTest {
        val config = SimulatorConfig().apply {
            elmVersion = "ELM327 v1.3a"
            stnDeviceId = "STN1110 v4.2.0"
        }
        val rig = Rig(config)
        rig.sim.connect()
        val info = rig.session.initialize(null) {}

        assertEquals(ChipFamily.STN, info.profile.family)
        assertFalse(info.profile.usesAdaptiveTiming2)
        assertFalse("ATAT2" in rig.sent())
    }

    @Test
    fun `genuine looking elm gets adaptive timing 2 on can and a refusal is recorded`() = runTest {
        val config = SimulatorConfig().apply {
            carProtocol = "6"
            elmVersion = "ELM327 v1.4b"
        }
        val rig = Rig(config)
        rig.sim.connect()
        val accepted = rig.session.initialize(null) {}
        assertEquals(ChipFamily.GENUINE_ELM, accepted.profile.family)
        assertTrue(accepted.profile.usesAdaptiveTiming2)

        config.cloneRejectsAt.add("ATAT2")
        val refused = Rig(config).run {
            sim.connect()
            session.initialize(null) {}
        }
        assertFalse(refused.profile.usesAdaptiveTiming2)
        assertTrue("ATAT2" in refused.unsupportedCommands)
    }

    @Test
    fun `adapter that rejects ati and at at 1 still initialises with an unknown family`() = runTest {
        val config = SimulatorConfig().apply { cloneRejectsAt.addAll(listOf("ATI", "AT@1")) }
        val rig = Rig(config)
        rig.sim.connect()
        val info = rig.session.initialize(null) {}

        assertEquals("unknown", info.elmVersion)
        assertEquals(ChipFamily.UNKNOWN, info.profile.family)
        assertNull(info.profile.firmware)
        assertNull(info.profile.description)
    }
}
