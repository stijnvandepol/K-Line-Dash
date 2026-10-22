package nl.stijnvdpol.klinedash.core.sim

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.assertIs
import nl.stijnvdpol.klinedash.core.diag.DtcFormat
import nl.stijnvdpol.klinedash.core.diag.DtcKind
import nl.stijnvdpol.klinedash.core.diag.DtcParser
import nl.stijnvdpol.klinedash.core.diag.VehicleInfoParser
import nl.stijnvdpol.klinedash.core.elm.ElmParser
import nl.stijnvdpol.klinedash.core.elm.ElmResult
import nl.stijnvdpol.klinedash.core.obd.MultiPidResponse
import nl.stijnvdpol.klinedash.core.obd.Signals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The simulator as a CAN car (ELM protocol 6): latency, multi-PID requests, multi-frame replies. */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorCanTest {

    private val lengths = Signals.all.mapNotNull { s -> s.pid?.let { p -> s.pidDataLength?.let { p to it } } }.toMap()

    private fun canConfig(configure: SimulatorConfig.() -> Unit = {}) = SimulatorConfig().apply {
        carProtocol = "6"
        configure()
    }

    private suspend fun SimulatorTransport.talk(cmd: String): String {
        discardPending()
        write("$cmd\r")
        return readUntilPrompt(20_000)
    }

    private suspend fun SimulatorTransport.send(cmd: String): ElmResult = ElmParser.parse(cmd, talk(cmd))

    /** Connected, protocol 6 selected, bus initialised, echo and spaces off. */
    private suspend fun SimulatorTransport.ready(): SimulatorTransport {
        connect()
        talk("ATE0")
        talk("ATSP6")
        talk("0100")
        return this
    }

    @Test
    fun `can car is detected as protocol 6 with the can name`() = runTest {
        val sim = SimulatorTransport(canConfig())
        sim.connect()
        sim.talk("ATE0")
        sim.talk("ATSP0")
        sim.talk("0100")
        assertEquals("A6", assertIs<ElmResult.Data>(sim.send("ATDPN")).lines.single())
        assertEquals("AUTO, ISO 15765-4 (CAN 11/500)", assertIs<ElmResult.Data>(sim.send("ATDP")).lines.single())
    }

    @Test
    fun `can replies come in 40 to 60 ms`() = runTest {
        val sim = SimulatorTransport(canConfig()).ready()
        repeat(10) {
            val before = currentTime
            sim.talk("010C")
            assertTrue(currentTime - before in 40L..60L)
        }
    }

    @Test
    fun `can bus init is short`() = runTest {
        val sim = SimulatorTransport(canConfig())
        sim.connect()
        sim.talk("ATSP6")
        val before = currentTime
        sim.talk("0100")
        assertTrue(currentTime - before < 1_000)
    }

    @Test
    fun `several pids in one request come back as one message with all blocks`() = runTest {
        val sim = SimulatorTransport(canConfig()).ready()
        sim.talk("ATS0")
        val data = assertIs<ElmResult.Data>(sim.send("010C0D"))

        assertEquals(1, data.lines.size)
        assertTrue(data.lines.single().startsWith("410C"))
        val blocks = MultiPidResponse.split(data, lengths)
        assertEquals(setOf(0x0C, 0x0D), blocks.keys)
        assertEquals(2, blocks.getValue(0x0C).size)
    }

    @Test
    fun `six pids fit and seven are too many`() = runTest {
        val sim = SimulatorTransport(canConfig { supportedPids = setOf(0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0F, 0x11) }).ready()

        val six = assertIs<ElmResult.Data>(sim.send("01040506070C0D"))
        assertEquals(6, MultiPidResponse.split(six, lengths).size)
        assertTrue(sim.send("01040506070C0D0F") is ElmResult.NoData)
    }

    @Test
    fun `unsupported pids are left out of a multi pid reply`() = runTest {
        val sim = SimulatorTransport(canConfig()).ready()
        val data = assertIs<ElmResult.Data>(sim.send("010C0B0D"))
        assertEquals(setOf(0x0C, 0x0D), MultiPidResponse.split(data, lengths).keys)
        assertTrue(sim.send("010B2A") is ElmResult.NoData)
    }

    @Test
    fun `a k-line car answers only single pid requests`() = runTest {
        val sim = SimulatorTransport()
        sim.connect()
        sim.talk("ATE0")
        sim.talk("ATSP3")
        sim.talk("0100")
        assertTrue(sim.send("010C0D") is ElmResult.NoData)
        assertTrue(sim.send("010C") is ElmResult.Data)
    }

    @Test
    fun `a response count digit works on a multi pid request`() = runTest {
        val sim = SimulatorTransport(canConfig()).ready()
        assertTrue(sim.send("010C0D1") is ElmResult.Data)
    }

    @Test
    fun `truncating clone answers only the first pid`() = runTest {
        val sim = SimulatorTransport(canConfig { longRequestQuirk = LongRequestQuirk.TRUNCATES }).ready()
        val data = assertIs<ElmResult.Data>(sim.send("010C0D11"))
        assertEquals(setOf(0x0C), MultiPidResponse.split(data, lengths).keys)
    }

    @Test
    fun `rejecting clone answers a question mark to a long request but not to a short one`() = runTest {
        val sim = SimulatorTransport(canConfig { longRequestQuirk = LongRequestQuirk.REJECTS }).ready()
        assertTrue(sim.send("010C0D") is ElmResult.Unknown)
        assertTrue(sim.send("010C") is ElmResult.Data)
    }

    // ---- multi-frame ----

    @Test
    fun `a message of more than seven bytes is printed as a length line and numbered frames`() = runTest {
        val sim = SimulatorTransport(canConfig()).ready()
        sim.talk("ATS0")
        val raw = sim.talk("010C0D11040506")
        val lines = raw.split("\r", "\n").filter { it.isNotBlank() }

        // 41, then 0C with two data bytes and five more PIDs with one = 14 bytes: 6 + 7 + 1.
        assertEquals("00E", lines[0])
        assertTrue(lines[1].startsWith("0: 410C"))
        assertEquals(listOf("1: ", "2: "), lines.drop(2).map { it.take(3) })
        assertEquals(4, lines.size)

        val data = assertIs<ElmResult.Data>(ElmParser.parse("010C0D11040506", raw))
        assertEquals(6, MultiPidResponse.split(data, lengths).size)
    }

    @Test
    fun `a vin is printed as a length line and three numbered frames`() = runTest {
        val vin = "1D4GP00R55B123456"
        val sim = SimulatorTransport(canConfig { this.vin = vin }).ready()
        // 49 02 01 and the 17 characters = 20 bytes: 6 + 7 + 7.
        val raw = sim.talk("0902")
        val lines = raw.split("\r", "\n").filter { it.isNotBlank() }
        assertEquals(listOf("014"), lines.take(1))
        assertEquals(listOf("0:", "1:", "2:"), lines.drop(1).map { it.take(2) })
    }

    @Test
    fun `vin is one multi frame message on can and the app parser reads it`() = runTest {
        val vin = "1D4GP00R55B123456"
        val sim = SimulatorTransport(canConfig { this.vin = vin }).ready()
        val data = assertIs<ElmResult.Data>(sim.send("0902"))
        assertEquals(vin, VehicleInfoParser.parseVin(data))
    }

    @Test
    fun `ecu name is read from can too`() = runTest {
        val sim = SimulatorTransport(canConfig { ecuName = "ECM-EngineControl" }).ready()
        val data = assertIs<ElmResult.Data>(sim.send("090A"))
        assertEquals("ECM-EngineControl", VehicleInfoParser.parseEcuName(data))
    }

    @Test
    fun `two stored codes fit one frame and carry a count byte`() = runTest {
        val sim = SimulatorTransport(canConfig()).ready()
        sim.talk("ATS0")
        val data = assertIs<ElmResult.Data>(sim.send("03"))

        assertEquals(listOf("430201711349"), data.lines)
        assertEquals(listOf("P0171", "P1349"), DtcParser.parse(data, DtcKind.STORED, DtcFormat.CAN)!!.map { it.code })
    }

    @Test
    fun `more than two codes come as a multi frame message`() = runTest {
        val sim = SimulatorTransport(canConfig { storedDtcs.add("P0420") }).ready()
        sim.talk("ATS0")
        val data = assertIs<ElmResult.Data>(sim.send("03"))

        // 43 03 + three codes = 8 bytes.
        assertEquals("008", data.lines.first())
        assertTrue(data.lines[1].startsWith("0:4303"))
        assertEquals(
            listOf("P0171", "P1349", "P0420"),
            DtcParser.parse(data, DtcKind.STORED, DtcFormat.CAN)!!.map { it.code },
        )
    }

    @Test
    fun `no codes still answers with a zero count`() = runTest {
        val sim = SimulatorTransport(canConfig { storedDtcs.clear() }).ready()
        sim.talk("ATS0")
        val data = assertIs<ElmResult.Data>(sim.send("03"))
        assertEquals(listOf("4300"), data.lines)
        assertTrue(DtcParser.parse(data, DtcKind.STORED, DtcFormat.CAN)!!.isEmpty())
    }

    @Test
    fun `headers stay off on can`() = runTest {
        val sim = SimulatorTransport(canConfig()).ready()
        sim.talk("ATH1")
        val data = assertIs<ElmResult.Data>(sim.send("010C"))
        assertTrue(data.lines.single().startsWith("410C"))
    }

    @Test
    fun `default simulator is still the k-line car`() = runTest {
        assertFalse(SimulatorConfig().isCanCar)
        assertTrue(canConfig().isCanCar)
        assertArrayEquals(arrayOf("6", "7", "8", "9"), SimulatorConfig.CAN_PROTOCOLS.sorted().toTypedArray())
    }
}
