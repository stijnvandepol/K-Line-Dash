package nl.stijnvdpol.klinedash.core.obd

import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import nl.stijnvdpol.klinedash.core.elm.AdapterInfo
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.elm.ElmConnectionManager
import nl.stijnvdpol.klinedash.core.elm.ElmLink
import nl.stijnvdpol.klinedash.core.elm.ElmResult
import nl.stijnvdpol.klinedash.core.elm.ProtocolStore
import nl.stijnvdpol.klinedash.core.log.CommLog
import nl.stijnvdpol.klinedash.core.log.LogKind
import nl.stijnvdpol.klinedash.core.log.RingCommLog
import nl.stijnvdpol.klinedash.core.sim.EngineModel
import nl.stijnvdpol.klinedash.core.sim.LongRequestQuirk
import nl.stijnvdpol.klinedash.core.sim.SimulatorConfig
import nl.stijnvdpol.klinedash.core.sim.SimulatorTransport
import nl.stijnvdpol.klinedash.core.sim.supportedPidBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A request without the ELM response-count digit the poller appends ("010C1" is "010C"). */
private fun withoutDigit(command: String): String =
    if (command.length % 2 == 1 && command.length >= 5) command.dropLast(1) else command

/** Multi-PID requests on CAN: packing, probing, fallback, and the whole thing against the simulator. */
@OptIn(ExperimentalCoroutinesApi::class)
class ObdPollSchedulerBatchingTest {

    // ---------------------------------------------------------------- packBatches

    private fun signal(id: String, pid: Int, length: Int?, tier: PollTier = PollTier.FAST): Signal = Signal(
        id, "01%02X".format(pid), pid, id, id, "", 0.0, 100.0, 0, tier,
        decodePayload = { it[0].toDouble() },
        pidDataLength = length,
    ) { null }

    private fun command(signal: Signal, intervalMs: Long = PollCommand.EVERY_ROUND) =
        PollCommand(signal.command, intervalMs, listOf(signal))

    private val rpm = signal("rpm", 0x0C, 2)
    private val speed = signal("speed", 0x0D, 1)
    private val throttle = signal("throttle", 0x11, 1)
    private val load = signal("load", 0x04, 1)
    private val lengths = mapOf(0x0C to 2, 0x0D to 1, 0x11 to 1, 0x04 to 1, 0x05 to 1)

    private fun List<PollCommand>.names() = map { it.command }

    @Test
    fun `fast commands are packed into single frame requests`() {
        val packed = packBatches(listOf(command(rpm), command(speed), command(throttle)), lengths, 6)

        // rpm (3 bytes) + speed (2) fill 5 of the 6; throttle (2) would make 7 and goes alone.
        assertEquals(listOf("010C0D", "0111"), packed.names())
        assertEquals(listOf("010C", "010D"), packed[0].members.map { it.command })
        assertFalse(packed[1].isBatch)
    }

    @Test
    fun `three one byte pids fit one frame`() {
        val packed = packBatches(listOf(command(speed), command(throttle), command(load)), lengths, 6)
        assertEquals(listOf("010D1104"), packed.names())
    }

    @Test
    fun `the pid limit is respected`() {
        val packed = packBatches(listOf(command(speed), command(throttle), command(load)), lengths, 2)
        assertEquals(listOf("010D11", "0104"), packed.names())
    }

    @Test
    fun `commands that cannot be batched stay as they are and come last`() {
        val coolant = signal("coolant", 0x05, 1, PollTier.MEDIUM)
        val battery = Signal("battery", "ATRV", null, "b", "b", "V", 0.0, 1.0, 1, PollTier.SLOW) { null }
        val unknownLength = signal("mystery", 0x2F, null)
        val plan = listOf(
            command(speed), command(coolant, intervalMs = 3_000), command(throttle),
            PollCommand("ATRV", 15_000, listOf(battery)), command(unknownLength),
        )
        val packed = packBatches(plan, lengths, 6)

        assertEquals(listOf("010D11", "0105", "ATRV", "012F"), packed.names())
    }

    @Test
    fun `a signal without a payload decoder is not batched`() {
        val noDecoder = Signal("raw", "0105", 0x05, "raw", "raw", "", 0.0, 1.0, 0, PollTier.FAST, pidDataLength = 1) { null }
        val packed = packBatches(listOf(command(speed), command(noDecoder)), lengths, 6)
        assertEquals(listOf("010D", "0105"), packed.names())
    }

    @Test
    fun `fewer than two batchable commands change nothing`() {
        val plan = listOf(command(speed))
        assertTrue(packBatches(plan, lengths, 6) === plan)
    }

    // ---------------------------------------------------------------- scripted link

    private val canInfo = AdapterInfo("ELM327 v1.5", "6", "ISO 15765-4 (CAN 11/500)", 12.4, false, emptySet())
    private val klineInfo = AdapterInfo("ELM327 v1.5", "3", "ISO 9141-2", 12.4, false, emptySet())

    private val blockData = mapOf(0x0C to "1AF8", 0x0D to "3C", 0x11 to "80", 0x05 to "50")

    private fun data(line: String) = ElmResult.Data(listOf(line), line)

    /** A CAN ECU that supports [blockData] and answers multi-PID requests in full. */
    private fun fullAnswer(request: String): ElmResult {
        if (request == "0100") {
            return data("4100" + supportedPidBitmap(blockData.keys, 0).joinToString("") { "%02X".format(it) })
        }
        val pids = request.drop(2).chunked(2).map { it.toInt(16) }
        val blocks = pids.mapNotNull { pid -> blockData[pid]?.let { "%02X".format(pid) + it } }
        return if (blocks.isEmpty()) ElmResult.NoData("NO DATA") else data("41" + blocks.joinToString(""))
    }

    private class ScriptedLink(info: AdapterInfo, private val responder: (String) -> ElmResult) : ElmLink {
        private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(info))
        override val state: StateFlow<ConnectionState> = _state
        override val log: CommLog = RingCommLog()
        val sent = ArrayList<String>()

        override fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun forgetSavedProtocol() = Unit

        override suspend fun execute(command: String, timeoutMs: Long): ElmResult {
            sent += command
            delay(LATENCY_MS)
            return responder(withoutDigit(command))
        }
    }

    private fun TestScope.poll(link: ScriptedLink, seconds: Long = 10): ObdPollScheduler {
        val poller = ObdPollScheduler(link, backgroundScope, Signals.all, { testScheduler.currentTime })
        poller.start()
        poller.demand("screen", setOf("rpm", "speed", "throttle"))
        advanceTimeBy(seconds * 1_000)
        runCurrent()
        return poller
    }

    private fun ScriptedLink.requests(): List<String> = sent.map { withoutDigit(it) }.filter { it !in DETECTION }

    private fun ScriptedLink.count(request: String) = requests().count { it == request }

    @Test
    fun `can car gets multi pid requests after the first round of single requests`() = runTest {
        val link = ScriptedLink(canInfo, ::fullAnswer)
        val poller = poll(link)

        assertEquals(listOf("010C", "010D", "0111"), link.requests().take(3))
        assertTrue(link.count("010C0D") > 5)
        assertTrue("batch keeps the response-count digit", "010C0D1" in link.sent)
        assertEquals(1726.0, poller.readings.value.getValue("rpm").value, 0.001)
        assertEquals(60.0, poller.readings.value.getValue("speed").value, 0.001)
        assertEquals(128 * 100.0 / 255.0, poller.readings.value.getValue("throttle").value, 0.001)
    }

    @Test
    fun `k-line car is never sent a multi pid request`() = runTest {
        val link = ScriptedLink(klineInfo) { request ->
            val pids = request.drop(2).chunked(2)
            if (request == "0100" || pids.size == 1) fullAnswer(request) else ElmResult.Unknown("?")
        }
        poll(link)

        assertTrue(link.requests().isNotEmpty())
        assertTrue(link.requests().all { it.length <= 4 })
    }

    @Test
    fun `no data for the batch and the singles is the ecu being silent and not the batch's fault`() = runTest {
        var silent = false
        val link = ScriptedLink(canInfo) { if (silent) ElmResult.NoData("NO DATA") else fullAnswer(it) }
        val poller = ObdPollScheduler(link, backgroundScope, Signals.all, { testScheduler.currentTime })
        poller.start()
        poller.demand("screen", setOf("rpm", "speed"))
        advanceTimeBy(1_000)
        runCurrent()
        silent = true
        advanceTimeBy(10_000)
        runCurrent()
        silent = false
        val before = link.count("010C0D")
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue("batching still on once the ECU answers again", link.count("010C0D") > before)
    }

    @Test
    fun `reply that holds only the first pid switches batching off and the singles still deliver`() = runTest {
        val link = ScriptedLink(canInfo) { request ->
            if (request.startsWith("010C0D")) data("410C1AF8") else fullAnswer(request)
        }
        val poller = poll(link)

        assertEquals(1, link.count("010C0D"))
        assertTrue(link.count("010D") > 3)
        assertEquals(60.0, poller.readings.value.getValue("speed").value, 0.001)
    }

    @Test
    fun `question mark for the batch switches batching off at once`() = runTest {
        val link = ScriptedLink(canInfo) { request ->
            if (request.startsWith("010C0D")) ElmResult.Unknown("?") else fullAnswer(request)
        }
        val poller = poll(link)

        assertEquals(1, link.count("010C0D"))
        assertEquals(1726.0, poller.readings.value.getValue("rpm").value, 0.001)
        assertTrue("the digit is not blamed", link.sent.any { it == "010C1" })
        assertTrue(link.sent.count { it == "010C1" } > 3)
    }

    @Test
    fun `two failed rounds in a row after it worked switch batching off`() = runTest {
        var batches = 0
        val link = ScriptedLink(canInfo) { request ->
            when {
                !request.startsWith("010C0D") -> fullAnswer(request)
                ++batches <= 2 -> fullAnswer(request)
                else -> data("410C1AF8")
            }
        }
        poll(link)

        assertEquals(4, link.count("010C0D"))
    }

    @Test
    fun `one bad round in between does not switch batching off`() = runTest {
        var batches = 0
        val link = ScriptedLink(canInfo) { request ->
            when {
                !request.startsWith("010C0D") -> fullAnswer(request)
                ++batches == 3 -> data("410C1AF8")
                else -> fullAnswer(request)
            }
        }
        poll(link)

        assertTrue(link.count("010C0D") > 6)
    }

    // ---------------------------------------------------------------- against the simulator

    private object NoStore : ProtocolStore {
        override suspend fun loadProtocol(adapterKey: String): String? = null
        override suspend fun saveProtocol(adapterKey: String, protocolNumber: String) = Unit
        override suspend fun clearProtocol(adapterKey: String) = Unit
    }

    private inner class SimRig(testScope: TestScope, val sim: SimulatorConfig) {
        val clock: () -> Long = { testScope.testScheduler.currentTime }
        val manager = ElmConnectionManager(
            { SimulatorTransport(sim, EngineModel(sim, Random(7)), clock) }, "sim", NoStore, testScope.backgroundScope,
            log = RingCommLog(clock = clock), random = Random(1),
        )
        val poller = ObdPollScheduler(manager, testScope.backgroundScope, Signals.all, clock)
        private val scope = testScope

        suspend fun run(seconds: Long = 10) {
            manager.start()
            withTimeout(60_000) { manager.state.first { it is ConnectionState.Connected } }
            manager.log.clear()
            poller.start()
            poller.demand("screen", setOf("rpm", "speed", "throttle"))
            scope.advanceTimeBy(seconds * 1_000)
            scope.runCurrent()
        }

        fun requests(): List<String> = manager.log.snapshot().filter { it.kind == LogKind.TX }
            .map { withoutDigit(it.text) }.filter { it !in DETECTION }
    }

    private fun canCar(configure: SimulatorConfig.() -> Unit = {}) = SimulatorConfig().apply {
        carProtocol = "6"
        configure()
    }

    @Test
    fun `simulated can car is polled with multi pid requests`() = runTest {
        val rig = SimRig(this, canCar())
        rig.run()

        assertTrue(rig.requests().count { it == "010C0D" } > 5)
        for (id in listOf("rpm", "speed", "throttle")) assertTrue(id, id in rig.poller.readings.value)
    }

    @Test
    fun `batching makes the simulated can car update faster`() = runTest {
        val batched = SimRig(this, canCar())
        batched.run()
        val batchedHz = batched.poller.stats.value.signalHz.getValue("rpm")

        val singles = SimRig(this, canCar { longRequestQuirk = LongRequestQuirk.REJECTS })
        singles.run()
        val singleHz = singles.poller.stats.value.signalHz.getValue("rpm")

        assertTrue("batched $batchedHz Hz vs single $singleHz Hz", batchedHz > singleHz * 1.2)
    }

    @Test
    fun `clone that truncates long requests falls back to singles with all signals intact`() = runTest {
        val rig = SimRig(this, canCar { longRequestQuirk = LongRequestQuirk.TRUNCATES })
        rig.run()

        assertEquals(1, rig.requests().count { it == "010C0D" })
        for (id in listOf("rpm", "speed", "throttle")) assertTrue(id, id in rig.poller.readings.value)
    }

    @Test
    fun `clone that rejects long requests falls back to singles`() = runTest {
        val rig = SimRig(this, canCar { longRequestQuirk = LongRequestQuirk.REJECTS })
        rig.run()

        assertEquals(1, rig.requests().count { it == "010C0D" })
        for (id in listOf("rpm", "speed", "throttle")) assertTrue(id, id in rig.poller.readings.value)
    }

    @Test
    fun `simulated k-line car stays on single requests`() = runTest {
        val rig = SimRig(this, SimulatorConfig())
        rig.run()

        assertTrue(rig.requests().isNotEmpty())
        assertTrue(rig.requests().all { it.length <= 4 })
    }

    private companion object {
        const val LATENCY_MS = 100L
        val DETECTION = setOf("0100", "0120", "0140", "0160", "0180", "01A0", "01C0", "01E0")
    }
}
