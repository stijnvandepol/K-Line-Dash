package nl.stijnvdpol.klinedash.core.sim

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.assertFails
import nl.stijnvdpol.klinedash.core.assertIs
import nl.stijnvdpol.klinedash.core.elm.ElmParser
import nl.stijnvdpol.klinedash.core.elm.ElmResult
import nl.stijnvdpol.klinedash.core.transport.TransportException
import nl.stijnvdpol.klinedash.core.transport.TransportTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorTransportTest {

    private suspend fun SimulatorTransport.talk(cmd: String, timeoutMs: Long = 20_000): String {
        discardPending()
        write("$cmd\r")
        return readUntilPrompt(timeoutMs)
    }

    private suspend fun SimulatorTransport.send(cmd: String): ElmResult = ElmParser.parse(cmd, talk(cmd))

    private suspend fun SimulatorTransport.lines(cmd: String): List<String> =
        assertIs<ElmResult.Data>(send(cmd)).lines

    /** Connected, protocol 3 selected, bus initialised, echo off. */
    private suspend fun SimulatorTransport.ready(): SimulatorTransport {
        connect()
        talk("ATSP3")
        talk("0100")
        talk("ATE0")
        return this
    }

    private fun bytes(hex: String): List<Int> = hex.chunked(2).map { it.toInt(16) }

    // ---- supported PIDs and values ----

    @Test
    fun `pid 00 bitmap matches the configured supported set`() = runTest {
        val sim = SimulatorTransport()
        sim.connect()
        sim.talk("ATSP3")
        val raw = sim.talk("0100")
        assertTrue(raw.contains("BUS INIT: ...OK"))

        val line = assertIs<ElmResult.Data>(ElmParser.parse("0100", raw)).lines.single()
        assertTrue(line.startsWith("4100"))
        val bitmap = line.substring(4).toLong(16)
        for (pid in 1..32) {
            val bit = (bitmap shr (32 - pid)) and 1L
            assertEquals("pid ${pid.toString(16)}", pid in SimulatorConfig.DEFAULT_SUPPORTED_PIDS, bit == 1L)
        }
        // 0x0B (MAP) must be absent on a MAF-based 2ZZ-GE, 0x20 present.
        assertFalse(SimulatorConfig.DEFAULT_SUPPORTED_PIDS.contains(0x0B))
        // Only 0x21 (distance with MIL) is advertised in the second block, 0x40 is not.
        assertEquals(listOf("412080000000"), sim.lines("0120"))
        assertIs<ElmResult.NoData>(sim.send("0140"))
    }

    @Test
    fun `default bitmap is the one read from the real car`() = runTest {
        val sim = SimulatorTransport().ready()
        // 01 03 04 05 06 07 | 0C 0D 0E 0F 10 | 11 12 13 14 15 | 1C 20, written out independently of the config.
        assertEquals(listOf("4100BE1FF811"), sim.lines("0100"))
    }

    @Test
    fun `fixed value pids of the real car`() = runTest {
        val sim = SimulatorTransport().ready()
        assertEquals(listOf("411204"), sim.lines("0112"))
        assertEquals(listOf("411303"), sim.lines("0113"))
        assertEquals(listOf("411C06"), sim.lines("011C"))
        assertEquals(listOf("41210000"), sim.lines("0121"))
    }

    @Test
    fun `bitmap follows a changed supported set`() = runTest {
        val config = SimulatorConfig().apply { supportedPids = setOf(0x0C, 0x0D) }
        val sim = SimulatorTransport(config).ready()
        // PIDs 0C and 0D are bits 20 and 19 from the top: 0x00180000.
        assertEquals(listOf("410000180000"), sim.lines("0100"))
        assertIs<ElmResult.NoData>(sim.send("0105"))
    }

    @Test
    fun `unsupported pid and unknown modes answer no data`() = runTest {
        val sim = SimulatorTransport().ready()
        assertIs<ElmResult.NoData>(sim.send("010B"))
        assertIs<ElmResult.NoData>(sim.send("0501"))
        assertIs<ElmResult.NoData>(sim.send("0A"))
        assertIs<ElmResult.NoData>(sim.send("0902"))
    }

    @Test
    fun `monitor status shows mil and dtc count`() = runTest {
        val sim = SimulatorTransport().ready()
        assertEquals(listOf("410182076504"), sim.lines("0101"))
        sim.config.storedDtcs.clear()
        assertEquals(listOf("410100076504"), sim.lines("0101"))
    }

    @Test
    fun `idle engine reports plausible values`() = runTest {
        val config = SimulatorConfig().apply {
            scenario = Scenario.IDLE
            initialCoolantC = 90.0
        }
        val sim = SimulatorTransport(config, clock = { currentTime }).ready()

        val rpmBytes = bytes(sim.lines("010C").single().substring(4))
        val rpm = (rpmBytes[0] * 256 + rpmBytes[1]) / 4
        assertTrue("rpm $rpm", rpm in 800..900)

        assertEquals(0, bytes(sim.lines("010D").single().substring(4)).single())
        val coolant = bytes(sim.lines("0105").single().substring(4)).single() - 40
        assertTrue("coolant $coolant", coolant in 88..92)
        val maf = bytes(sim.lines("0110").single().substring(4)).let { (it[0] * 256 + it[1]) / 100.0 }
        assertTrue("maf $maf", maf in 2.0..6.0)
        // Closed loop at operating temperature.
        assertEquals(listOf("410302" + "00"), sim.lines("0103"))
    }

    @Test
    fun `warm idle matches the real car`() = runTest {
        val config = SimulatorConfig().apply {
            scenario = Scenario.IDLE
            initialCoolantC = 90.0
        }
        val sim = SimulatorTransport(config, clock = { currentTime }).ready()

        val rpmBytes = bytes(sim.lines("010C").single().substring(4))
        val rpm = (rpmBytes[0] * 256 + rpmBytes[1]) / 4
        assertTrue("rpm $rpm", rpm in 815..845)
        // Throttle reads about 9.4 % with the pedal released, not 0.
        val throttle = bytes(sim.lines("0111").single().substring(4)).single() * 100.0 / 255.0
        assertTrue("throttle $throttle", throttle in 8.5..10.5)
    }

    @Test
    fun `default coolant starts at 46 and warms towards 90`() = runTest {
        val config = SimulatorConfig().apply { scenario = Scenario.IDLE }
        val sim = SimulatorTransport(config, clock = { currentTime }).ready()

        val start = bytes(sim.lines("0105").single().substring(4)).single() - 40
        assertTrue("coolant $start", start in 45..50)
        delay(600_000)
        val warm = bytes(sim.lines("0105").single().substring(4)).single() - 40
        assertTrue("coolant $warm", warm in 85..92)
    }

    @Test
    fun `engine off reports zero rpm and lower battery voltage`() = runTest {
        val config = SimulatorConfig().apply {
            scenario = Scenario.IDLE
            initialCoolantC = 90.0
        }
        val sim = SimulatorTransport(config, clock = { currentTime }).ready()
        sim.setEngineRunning(false)
        delay(2_000)
        assertEquals(listOf("410C0000"), sim.lines("010C"))
        assertEquals(listOf("410D00"), sim.lines("010D"))
        assertEquals(listOf("12.4V"), sim.lines("ATRV"))

        sim.setEngineRunning(true)
        delay(2_000)
        assertEquals(listOf("13.9V"), sim.lines("ATRV"))
        assertFalse(sim.lines("010C").single() == "410C0000")
    }

    // ---- DTCs ----

    @Test
    fun `mode 03 encodes stored dtcs in k-line frames`() = runTest {
        val sim = SimulatorTransport().ready()
        assertEquals(listOf("43017113490000"), sim.lines("03"))
    }

    @Test
    fun `mode 03 splits more than three dtcs over frames`() = runTest {
        val sim = SimulatorTransport().ready()
        sim.config.storedDtcs.addAll(listOf("P0420", "U1234"))
        assertEquals(listOf("43017113490420", "43D2340000" + "0000"), sim.lines("03"))
    }

    @Test
    fun `mode 03 without codes still answers with zeros`() = runTest {
        val sim = SimulatorTransport().ready()
        sim.config.storedDtcs.clear()
        assertEquals(listOf("43000000000000"), sim.lines("03"))
    }

    @Test
    fun `mode 07 returns pending codes`() = runTest {
        val sim = SimulatorTransport().ready()
        assertEquals(listOf("47030000000000"), sim.lines("07"))
    }

    @Test
    fun `mode 04 clears codes and the mil`() = runTest {
        val sim = SimulatorTransport().ready()
        assertEquals(listOf("44"), sim.lines("04"))
        assertEquals(listOf("43000000000000"), sim.lines("03"))
        assertEquals(listOf("47000000000000"), sim.lines("07"))
        // Like a real ECU: no codes, but every monitor has to run again.
        assertEquals(listOf("410100776565"), sim.lines("0101"))
        assertIs<ElmResult.NoData>(sim.send("0202"))
    }

    @Test
    fun `mode 04 is ignored when the ecu refuses to clear`() = runTest {
        val sim = SimulatorTransport(SimulatorConfig().apply { refusesClear = true }).ready()
        assertIs<ElmResult.NoData>(sim.send("04"))
        assertEquals(listOf("43017113490000"), sim.lines("03"))
        assertEquals(listOf("410182076504"), sim.lines("0101"))
    }

    @Test
    fun `freeze frame mode 02`() = runTest {
        val sim = SimulatorTransport().ready()
        assertEquals(listOf("4202000171"), sim.lines("0202"))
        assertEquals(listOf("4205007D"), sim.lines("0205"))
        assertEquals(listOf("4205007D"), sim.lines("020500"))
        assertEquals(listOf("420C0020D0"), sim.lines("020C"))
        assertEquals(listOf("420D0038"), sim.lines("020D"))
        assertIs<ElmResult.NoData>(sim.send("020501"))
        assertIs<ElmResult.NoData>(sim.send("0214"))
    }

    @Test
    fun `freeze frame bitmap lists the stored pids`() = runTest {
        val sim = SimulatorTransport().ready()
        // PIDs 02, 04-07 -> 0x5E; 0C-10 -> 0x1F; 11 -> 0x80.
        assertEquals(listOf("4200005E1F8000"), sim.lines("020000"))
    }

    @Test
    fun `freeze frame follows the first stored code`() = runTest {
        val sim = SimulatorTransport().ready()
        sim.config.storedDtcs.clear()
        sim.config.storedDtcs.add("U1234")
        assertEquals(listOf("420200D234"), sim.lines("020200"))
    }

    @Test
    fun `dtc encoding`() {
        assertEquals(Pair(0x01, 0x71), SimulatorDiagnosticsEcu.encodeDtc("P0171"))
        assertEquals(Pair(0x13, 0x49), SimulatorDiagnosticsEcu.encodeDtc("P1349"))
        assertEquals(Pair(0x43, 0x00), SimulatorDiagnosticsEcu.encodeDtc("C0300"))
        assertEquals(Pair(0x9A, 0xBC), SimulatorDiagnosticsEcu.encodeDtc("B1ABC"))
        assertEquals(Pair(0xD2, 0x34), SimulatorDiagnosticsEcu.encodeDtc("u1234"))
        assertNull(SimulatorDiagnosticsEcu.encodeDtc("X0000"))
        assertNull(SimulatorDiagnosticsEcu.encodeDtc("P4000"))
        assertNull(SimulatorDiagnosticsEcu.encodeDtc("P01"))
        assertNull(SimulatorDiagnosticsEcu.encodeDtc("P01G1"))
    }

    // ---- mode 09 ----

    @Test
    fun `mode 09 vin comes as five k-line frames`() = runTest {
        val config = SimulatorConfig().apply { vin = "SB1ZE22E10E123456" }
        val sim = SimulatorTransport(config).ready()
        assertEquals(
            listOf(
                "49020100000053",
                "490202" + "42315A45",
                "490203" + "32324531",
                "490204" + "30453132",
                "490205" + "33343536",
            ),
            sim.lines("0902"),
        )
    }

    @Test
    fun `mode 09 ecu name and bitmap`() = runTest {
        val config = SimulatorConfig().apply {
            vin = "SB1ZE22E10E123456"
            ecuName = "KLINE-ECU"
        }
        val sim = SimulatorTransport(config).ready()
        assertEquals(listOf("490040400000"), sim.lines("0900"))
        val name = sim.lines("090A")
        assertEquals(5, name.size)
        assertEquals("490A01" + "4B4C494E", name[0])
        assertEquals("490A05" + "00000000", name[4])
    }

    // ---- protocol and adapter state ----

    @Test
    fun `auto search shows searching and takes the bus init time`() = runTest {
        val sim = SimulatorTransport()
        sim.connect()
        assertEquals(listOf("A0"), sim.lines("ATDPN"))
        assertEquals(listOf("AUTO"), sim.lines("ATDP"))

        sim.talk("ATSP0")
        val before = currentTime
        val raw = sim.talk("0100")
        assertTrue(raw.contains("SEARCHING..."))
        assertTrue("took ${currentTime - before}", currentTime - before >= 2_500)

        val second = currentTime
        sim.talk("010C")
        assertTrue(currentTime - second < 1_000)

        assertEquals(listOf("A3"), sim.lines("ATDPN"))
        assertEquals(listOf("AUTO, ISO 9141-2"), sim.lines("ATDP"))
    }

    @Test
    fun `fixed protocol reports plain number and name`() = runTest {
        val sim = SimulatorTransport()
        sim.connect()
        assertEquals(listOf("OK"), sim.lines("ATSP3"))
        assertEquals(listOf("3"), sim.lines("ATDPN"))
        assertEquals(listOf("ISO 9141-2"), sim.lines("ATDP"))
    }

    @Test
    fun `wrong fixed protocol fails to init and auto recovers`() = runTest {
        val sim = SimulatorTransport()
        sim.connect()
        sim.talk("ATSP5")
        assertIs<ElmResult.InitError>(sim.send("0100"))
        sim.talk("ATSP0")
        assertIs<ElmResult.Data>(sim.send("0100"))
    }

    @Test
    fun `configured init failure`() = runTest {
        val config = SimulatorConfig().apply { initFails = true }
        val sim = SimulatorTransport(config)
        sim.connect()
        sim.talk("ATSP3")
        val raw = sim.talk("0100")
        assertTrue(raw.contains("BUS INIT: ...ERROR"))
        assertIs<ElmResult.InitError>(ElmParser.parse("0100", raw))
    }

    @Test
    fun `identity and unknown commands`() = runTest {
        val sim = SimulatorTransport()
        sim.connect()
        assertEquals(listOf("ELM327 v1.5"), sim.lines("ATI"))
        assertEquals(listOf("OBDII to RS232 Interpreter"), sim.lines("AT@1"))
        assertEquals(listOf("OK"), sim.lines("ATAT1"))
        assertIs<ElmResult.Unknown>(sim.send("ATFOO"))
        assertIs<ElmResult.Unknown>(sim.send("ATSPZ"))
        assertIs<ElmResult.Unknown>(sim.send("01G"))
    }

    @Test
    fun `clone can reject chosen at commands`() = runTest {
        val config = SimulatorConfig().apply { cloneRejectsAt.addAll(listOf("ATAT1", "ATLP", "AT@1")) }
        val sim = SimulatorTransport(config)
        sim.connect()
        assertIs<ElmResult.Unknown>(sim.send("ATAT1"))
        assertIs<ElmResult.Unknown>(sim.send("ATLP"))
        assertIs<ElmResult.Unknown>(sim.send("AT@1"))
        assertEquals(listOf("OK"), sim.lines("ATAT0"))
    }

    @Test
    fun `echo toggles`() = runTest {
        val sim = SimulatorTransport()
        sim.connect()
        assertEquals("ATI\r\nELM327 v1.5\r\n\r\n", sim.talk("ATI"))
        // The echo of the command itself still follows the old state.
        assertEquals("ATE0\r\nOK\r\n\r\n", sim.talk("ATE0"))
        assertEquals("ELM327 v1.5\r\n\r\n", sim.talk("ATI"))
        assertEquals("OK\r\n\r\n", sim.talk("ATE1"))
        assertEquals("ATI\r\nELM327 v1.5\r\n\r\n", sim.talk("ATI"))
    }

    @Test
    fun `linefeed toggle`() = runTest {
        val sim = SimulatorTransport()
        sim.connect()
        sim.talk("ATE0")
        assertEquals("OK\r\r", sim.talk("ATL0"))
        assertEquals("ELM327 v1.5\r\r", sim.talk("ATI"))
        assertEquals("OK\r\n\r\n", sim.talk("ATL1"))
    }

    @Test
    fun `spaces toggle`() = runTest {
        val sim = SimulatorTransport().ready()
        assertTrue(Regex("^41 0C [0-9A-F]{2} [0-9A-F]{2} \r\n\r\n$").matches(sim.talk("010C")))
        sim.talk("ATS0")
        assertTrue(Regex("^410C[0-9A-F]{4}\r\n\r\n$").matches(sim.talk("010C")))
        sim.talk("ATS1")
        assertTrue(Regex("^41 0C [0-9A-F]{2} [0-9A-F]{2} \r\n\r\n$").matches(sim.talk("010C")))
    }

    @Test
    fun `headers add the k-line header and checksum`() = runTest {
        val sim = SimulatorTransport().ready()
        sim.talk("ATH1")
        sim.talk("ATS0")
        val line = sim.lines("010D").single()
        val b = bytes(line)
        assertEquals(listOf(0x48, 0x6B, 0x10, 0x41, 0x0D), b.take(5))
        assertEquals(b.dropLast(1).sum() and 0xFF, b.last())
        assertEquals(7, b.size)

        sim.talk("ATH0")
        assertEquals(3, bytes(sim.lines("010D").single()).size)
    }

    @Test
    fun `atz resets echo spaces and protocol`() = runTest {
        val sim = SimulatorTransport().ready()
        sim.talk("ATS0")
        val raw = sim.talk("ATZ")
        assertTrue(raw.contains("ELM327 v1.5"))
        assertEquals("ATI\r\nELM327 v1.5\r\n\r\n", sim.talk("ATI"))
        assertEquals(listOf("A0"), sim.lines("ATDPN"))
    }

    // ---- fault injection and transport behaviour ----

    @Test
    fun `random no data rate`() = runTest {
        val sim = SimulatorTransport().ready()
        sim.config.randomNoDataRate = 1.0
        assertIs<ElmResult.NoData>(sim.send("010C"))
        sim.config.randomNoDataRate = 0.0
        assertIs<ElmResult.Data>(sim.send("010C"))
    }

    @Test
    fun `garbage rate injects stray characters but no nul or high bytes`() = runTest {
        val sim = SimulatorTransport().ready()
        sim.config.garbageRate = 1.0
        repeat(10) {
            val raw = sim.talk("010C")
            assertFalse(raw.any { it == '\u0000' || it.code > 0x7F })
            assertTrue(raw.any { it in "#@%*" })
        }
    }

    @Test
    fun `timeout rate leaves the read hanging until the timeout`() = runTest {
        val sim = SimulatorTransport().ready()
        sim.config.timeoutRate = 1.0
        val before = currentTime
        val e = assertFails<TransportTimeoutException> { sim.talk("010C", timeoutMs = 700) }
        assertEquals(700L, currentTime - before)
        assertEquals("", e.partial)
    }

    @Test
    fun `reply slower than the timeout reports the partial text`() = runTest {
        val sim = SimulatorTransport()
        sim.connect()
        sim.talk("ATSP0")
        val e = assertFails<TransportTimeoutException> { sim.talk("0100", timeoutMs = 1_000) }
        assertEquals("0100\r\nSEARCHING...\r\n", e.partial)
    }

    @Test
    fun `spontaneous reset answers with the banner`() = runTest {
        val sim = SimulatorTransport().ready()
        sim.config.spontaneousResetRate = 1.0
        val raw = sim.talk("010C")
        assertTrue(ElmParser.looksLikeReset(raw))
        assertIs<ElmResult.Garbage>(ElmParser.parse("010C", raw))
    }

    @Test
    fun `connection drop after n commands is one shot`() = runTest {
        val config = SimulatorConfig().apply { dropConnectionAfterCommands = 2 }
        val sim = SimulatorTransport(config)
        sim.connect()
        sim.talk("ATI")
        sim.talk("ATI")
        assertFails<TransportException> { sim.talk("ATI") }
        assertFalse(sim.isConnected)
        assertNull(config.dropConnectionAfterCommands)

        val fresh = SimulatorTransport(config)
        fresh.connect()
        repeat(5) { fresh.talk("ATI") }
        assertTrue(fresh.isConnected)
    }

    @Test
    fun `connect failure and not connected errors`() = runTest {
        val config = SimulatorConfig().apply { failConnect = true }
        val sim = SimulatorTransport(config)
        assertFails<TransportException> { sim.connect() }
        assertFails<TransportException> { sim.write("ATI\r") }
        config.failConnect = false
        sim.connect()
        assertTrue(sim.isConnected)
        sim.close()
        assertFalse(sim.isConnected)
        assertFails<TransportException> { sim.readUntilPrompt(100) }
    }

    @Test
    fun `overlapping commands are rejected`() = runTest {
        val sim = SimulatorTransport()
        sim.connect()
        val first = async { sim.talk("ATI") }
        runCurrent() // first command is now waiting out its latency
        // A real adapter would abort the running command ("STOPPED"); the simulator flags it.
        assertFails<IllegalStateException> { sim.write("ATI\r") }
        first.await()
    }

    @Test
    fun `same seed gives identical garbage`() = runTest {
        suspend fun capture(): List<String> {
            val config = SimulatorConfig().apply { garbageRate = 0.5 }
            val sim = SimulatorTransport(config).ready()
            return List(20) { sim.talk("0113") }
        }
        assertEquals(capture(), capture())
    }

    @Test
    fun `default latency is the measured 450 to 500 ms per request`() = runTest {
        val sim = SimulatorTransport().ready()
        repeat(30) {
            val t0 = currentTime
            sim.talk("010C")
            val dt = currentTime - t0
            assertTrue("latency $dt", dt in 450L..500L)
        }
    }

    @Test
    fun `request with response count digit is accepted and answers faster`() = runTest {
        val sim = SimulatorTransport().ready()
        repeat(30) {
            val t0 = currentTime
            val line = sim.lines("010C1").single()
            val dt = currentTime - t0
            assertTrue("latency $dt", dt in 120L..160L)
            assertTrue(line.startsWith("410C"))
        }
        // A one-byte request takes the digit too: mode 03 with count 1.
        assertEquals(listOf("43017113490000"), sim.lines("031"))
    }

    @Test
    fun `count digit applies to no data answers as well`() = runTest {
        val sim = SimulatorTransport().ready()
        assertIs<ElmResult.NoData>(sim.send("010B1"))
    }

    @Test
    fun `clone that rejects the response count answers a question mark`() = runTest {
        val config = SimulatorConfig().apply { cloneRejectsResponseCount = true }
        val sim = SimulatorTransport(config).ready()
        assertIs<ElmResult.Unknown>(sim.send("010C1"))
        assertIs<ElmResult.Unknown>(sim.send("031"))
        assertIs<ElmResult.Data>(sim.send("010C"))
    }

    @Test
    fun `lone count digit and non hex input stay unknown`() = runTest {
        val sim = SimulatorTransport().ready()
        assertIs<ElmResult.Unknown>(sim.send("1"))
        assertIs<ElmResult.Unknown>(sim.send("010G1"))
    }

    @Test
    fun `character sent to a busy adapter gives stopped and a prompt`() = runTest {
        val sim = SimulatorTransport().ready()
        // The reply would take 450 ms; the reader gives up after 100 ms, the adapter is still busy.
        assertFails<TransportTimeoutException> { sim.talk("010C", timeoutMs = 100) }

        assertIs<ElmResult.Stopped>(ElmParser.parse("", sim.talk(" ")))
        // Now the adapter is idle again.
        assertIs<ElmResult.Data>(sim.send("010C"))
    }

    @Test
    fun `latency of an obd request stays in the configured range`() = runTest {
        val config = SimulatorConfig().apply {
            latencyMinMs = 60
            latencyMaxMs = 120
        }
        val sim = SimulatorTransport(config).ready()
        repeat(30) {
            val t0 = currentTime
            sim.talk("010C")
            val dt = currentTime - t0
            assertTrue("latency $dt", dt in 60L..120L)
        }
    }
}
