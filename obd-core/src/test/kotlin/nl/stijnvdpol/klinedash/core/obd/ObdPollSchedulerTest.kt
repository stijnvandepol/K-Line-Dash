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
import nl.stijnvdpol.klinedash.core.assertFails
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
import nl.stijnvdpol.klinedash.core.sim.SimulatorConfig
import nl.stijnvdpol.klinedash.core.sim.SimulatorTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObdPollSchedulerTest {

    // ---------------------------------------------------------------- fixtures

    private fun pidSignal(id: String, pid: Int, tier: PollTier, decode: (IntArray) -> Double?): Signal =
        Signal(id, "01%02X".format(pid), pid, id, id, "", 0.0, 100.0, 0, tier) { data ->
            ObdReply.payload(data, 1, pid)?.let(decode)
        }

    /** Small catalog with known commands and tiers, so the tests do not depend on [Signals]. */
    private val catalog: List<Signal> = listOf(
        pidSignal("rpm", 0x0C, PollTier.FAST) { (it[0] * 256 + it[1]) / 4.0 },
        pidSignal("speed", 0x0D, PollTier.FAST) { it[0].toDouble() },
        pidSignal("throttle", 0x11, PollTier.FAST) { it[0] * 100.0 / 255.0 },
        pidSignal("coolant", 0x05, PollTier.MEDIUM) { it[0] - 40.0 },
        pidSignal("intake", 0x0F, PollTier.MEDIUM) { it[0] - 40.0 },
        pidSignal("trim", 0x06, PollTier.SLOW) { it[0] * 100.0 / 128.0 - 100.0 },
        pidSignal("o2_v", 0x14, PollTier.SLOW) { it[0] / 200.0 },
        pidSignal("o2_trim", 0x14, PollTier.SLOW) { it[1] * 100.0 / 128.0 - 100.0 },
        Signal("battery", "ATRV", null, "battery", "battery", "V", 0.0, 20.0, 1, PollTier.SLOW) { data ->
            data.lines.firstOrNull()?.removeSuffix("V")?.toDoubleOrNull()
        },
    )

    private object NoStore : ProtocolStore {
        override suspend fun loadProtocol(adapterKey: String): String? = null
        override suspend fun saveProtocol(adapterKey: String, protocolNumber: String) = Unit
        override suspend fun clearProtocol(adapterKey: String) = Unit
    }

    /** Real connection manager over the simulator, all on the test scheduler's virtual clock. */
    private inner class Rig(
        private val testScope: TestScope,
        val sim: SimulatorConfig = SimulatorConfig(),
        pollerConfig: PollerConfig = PollerConfig(),
    ) {
        val clock: () -> Long = { testScope.testScheduler.currentTime }
        val engine = EngineModel(sim, Random(7))
        val manager = ElmConnectionManager(
            { SimulatorTransport(sim, engine, clock) }, "sim", NoStore, testScope.backgroundScope,
            log = RingCommLog(clock = clock), random = Random(1),
        )
        val poller = ObdPollScheduler(manager, testScope.backgroundScope, catalog, clock, pollerConfig)

        /** Connects and forgets the init traffic, so the log only holds what the poller sends. */
        suspend fun connect() {
            manager.start()
            withTimeout(60_000) { manager.state.first { it is ConnectionState.Connected } }
            manager.log.clear()
        }

        /** (virtual time, command) of everything the poller sent, without the PID-detection requests. */
        fun sent(): List<Pair<Long, String>> = manager.log.snapshot()
            .filter { it.kind == LogKind.TX && normalize(it.text) !in DETECTION_COMMANDS }
            .map { it.timestampMs to normalize(it.text) }

        fun commands(): List<String> = sent().map { it.second }

        suspend fun elapse(ms: Long) {
            testScope.advanceTimeBy(ms)
            testScope.runCurrent()
        }
    }

    /** "010C1" (with response count) and "010C" are the same request for these tests. */
    private fun normalize(text: String): String =
        if (text.length == 5 && text.startsWith("01")) text.dropLast(1) else text

    /** Drops the immediate repeat that the response-count fallback causes. */
    private fun List<String>.collapsed(): List<String> =
        filterIndexed { i, c -> i == 0 || c != this[i - 1] }

    // ---------------------------------------------------------------- fake link (suffix, detection retry)

    private val adapterInfo = AdapterInfo("ELM327 v1.5", "3", "ISO 9141-2", 12.4, false, emptySet())

    private fun data(line: String) = ElmResult.Data(listOf(line), line)

    /** A K-line car that supports PIDs 05, 0C and 0D; [acceptsSuffix] says whether "010C1" is understood. */
    private fun ecuAnswer(command: String, acceptsSuffix: Boolean): ElmResult {
        val hasSuffix = command.length == 5 && command.startsWith("01")
        if (hasSuffix && !acceptsSuffix) return ElmResult.Unknown("?")
        return when (if (hasSuffix) command.dropLast(1) else command) {
            "0100" -> data("410008180000")
            "010C" -> data("410C1AF8")
            "010D" -> data("410D32")
            "0105" -> data("410550")
            else -> ElmResult.NoData("NO DATA")
        }
    }

    private class FakeLink(
        private val responder: (String) -> ElmResult,
    ) : ElmLink {
        private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override val state: StateFlow<ConnectionState> = _state
        override val log: CommLog = RingCommLog()
        val sent = ArrayList<String>()

        fun setState(value: ConnectionState) {
            _state.value = value
        }

        override fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun forgetSavedProtocol() = Unit

        override suspend fun execute(command: String, timeoutMs: Long): ElmResult {
            check(_state.value is ConnectionState.Connected) { "not connected" }
            sent += command
            delay(FAKE_LATENCY_MS)
            return responder(command)
        }
    }

    private fun fakePoller(scope: TestScope, link: ElmLink, config: PollerConfig = PollerConfig()) =
        ObdPollScheduler(link, scope.backgroundScope, catalog, { scope.testScheduler.currentTime }, config)

    // ---------------------------------------------------------------- what is sent

    @Test
    fun `only demanded and supported signals are sent`() = runTest {
        val rig = Rig(this)
        rig.sim.supportedPids = SimulatorConfig.DEFAULT_SUPPORTED_PIDS - 0x11
        rig.connect()
        rig.poller.start()
        rig.poller.demand("screen", setOf("rpm", "coolant", "throttle", "no_such_signal"))
        rig.elapse(10_000)

        assertEquals(setOf("010C", "0105"), rig.commands().toSet())
        assertEquals(setOf("rpm", "coolant"), rig.poller.readings.value.keys)
        assertEquals(setOf("throttle"), rig.poller.stats.value.unsupported)
        assertEquals(SimulatorConfig.DEFAULT_SUPPORTED_PIDS - 0x11 - 0x20, rig.poller.supportedPids.value)
    }

    @Test
    fun `adapter commands without a pid are polled without a support check`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.poller.start()
        rig.poller.demand("screen", setOf("battery"))
        rig.elapse(2_000)

        assertTrue("ATRV" in rig.commands())
        assertEquals(13.9, rig.poller.readings.value.getValue("battery").value, 0.001)
    }

    @Test
    fun `nothing demanded sends nothing`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.poller.start()
        rig.elapse(30_000)

        assertTrue(rig.commands().isEmpty())
        assertNotNull(rig.poller.supportedPids.value)
        assertEquals(PollMode.ACTIVE, rig.poller.stats.value.mode)
    }

    @Test
    fun `demanding only unsupported signals sends nothing`() = runTest {
        val rig = Rig(this)
        rig.sim.supportedPids = SimulatorConfig.DEFAULT_SUPPORTED_PIDS - 0x11
        rig.connect()
        rig.poller.start()
        rig.poller.demand("screen", setOf("throttle"))
        rig.elapse(30_000)

        assertTrue(rig.commands().isEmpty())
        assertEquals(setOf("throttle"), rig.poller.stats.value.unsupported)
    }

    @Test
    fun `a command shared by two signals is sent once and decodes both`() = runTest {
        // This test only has one second of virtual time; the default K-line latency would not fit.
        val fastSim = SimulatorConfig().apply {
            latencyMinMs = 60
            latencyMaxMs = 120
        }
        val rig = Rig(this, fastSim)
        rig.connect()
        rig.poller.start()
        rig.poller.demand("screen", setOf("o2_v", "o2_trim"))
        rig.elapse(1_000)

        assertEquals(1, rig.commands().collapsed().count { it == "0114" })
        assertEquals(setOf("o2_v", "o2_trim"), rig.poller.readings.value.keys.filter { it.startsWith("o2") }.toSet())
    }

    @Test
    fun `release stops polling that signal while other owners continue`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.poller.start()
        rig.poller.demand("a", setOf("speed"))
        rig.poller.demand("b", setOf("coolant"))
        rig.elapse(5_000)
        assertTrue("010D" in rig.commands())

        rig.poller.release("a")
        rig.elapse(1_000) // lets an in-flight request finish
        val speedCount = rig.commands().count { it == "010D" }
        rig.elapse(20_000)

        assertEquals(speedCount, rig.commands().count { it == "010D" })
        assertTrue(rig.commands().count { it == "0105" } > 3)
    }

    @Test
    fun `replacing an owners demand drops the old signals`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.poller.start()
        rig.poller.demand("screen", setOf("speed"))
        rig.elapse(3_000)
        rig.poller.demand("screen", setOf("throttle"))
        rig.elapse(1_000)
        val speedCount = rig.commands().count { it == "010D" }
        rig.elapse(5_000)

        assertEquals(speedCount, rig.commands().count { it == "010D" })
        assertTrue("0111" in rig.commands())
    }

    // ---------------------------------------------------------------- tiers

    @Test
    fun `fast every round, medium and slow take the rotating slot at their intervals`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.poller.start()
        rig.poller.demand("screen", setOf("rpm", "speed", "coolant", "trim"))
        rig.elapse(50_000)

        val fast = setOf("010C", "010D")
        val sequence = rig.commands().collapsed()
        assertTrue("two slot commands in a row", sequence.zipWithNext().none { (a, b) -> a !in fast && b !in fast })
        assertTrue(Math.abs(sequence.count { it == "010C" } - sequence.count { it == "010D" }) <= 1)

        val coolantGaps = gapsOf(rig, "0105")
        assertTrue(coolantGaps.size > 10)
        assertTrue("coolant gaps $coolantGaps", coolantGaps.all { it in 3_000..4_500 })
        val trimGaps = gapsOf(rig, "0106")
        assertTrue(trimGaps.size >= 2)
        assertTrue("trim gaps $trimGaps", trimGaps.all { it in 15_000..16_500 })
    }

    private fun gapsOf(rig: Rig, command: String): List<Long> =
        rig.sent().filter { it.second == command }.map { it.first }.zipWithNext { a, b -> b - a }

    @Test
    fun `with nothing fast the slot still runs and the loop sleeps in between`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.poller.start()
        rig.poller.demand("screen", setOf("coolant"))
        rig.elapse(30_000)

        val times = rig.sent().filter { it.second == "0105" }.map { it.first }
        assertTrue(times.size in 9..11)
        // Only coolant plus the RPM engine-off probe, nothing else.
        assertEquals(setOf("0105", "010C"), rig.commands().toSet())
    }

    // ---------------------------------------------------------------- NO DATA

    @Test
    fun `repeated no data marks the signal unsupported for the connection`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.poller.start()
        rig.poller.demand("screen", setOf("speed", "coolant"))
        rig.elapse(2_000)
        // The ECU's bitmap lied: from now on it does not answer 0105.
        rig.sim.supportedPids = SimulatorConfig.DEFAULT_SUPPORTED_PIDS - 0x05
        rig.manager.log.clear()
        rig.elapse(30_000)

        assertEquals(setOf("coolant"), rig.poller.stats.value.unsupported)
        assertEquals(3, rig.commands().count { it == "0105" })
        assertTrue(rig.commands().count { it == "010D" } > 10)
    }

    @Test
    fun `no data on every command including rpm does not mark anything unsupported`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.poller.start()
        rig.poller.demand("screen", setOf("coolant", "speed"))
        rig.elapse(2_000)
        rig.sim.randomNoDataRate = 1.0 // ignition off with a live adapter
        rig.elapse(60_000)

        assertTrue(rig.poller.stats.value.unsupported.isEmpty())
    }

    // ---------------------------------------------------------------- response-count suffix

    @Test
    fun `obd commands carry the response count and adapter commands do not`() = runTest {
        val link = FakeLink { ecuAnswer(it, acceptsSuffix = true) }
        link.setState(ConnectionState.Connected(adapterInfo))
        val poller = fakePoller(this, link)
        poller.start()
        poller.demand("screen", setOf("rpm", "speed"))
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        val polled = link.sent.filter { it !in DETECTION_COMMANDS }
        assertTrue(polled.isNotEmpty())
        assertTrue(polled.toString(), polled.all { it == "010C1" || it == "010D1" })
        assertEquals(1.0 * 0x1AF8 / 4, poller.readings.value.getValue("rpm").value, 0.001)
    }

    @Test
    fun `an adapter that hangs on the response count falls back instead of timing out forever`() = runTest {
        val link = FakeLink { command ->
            if (command.length == 5) ElmResult.Timeout("") else ecuAnswer(command, acceptsSuffix = false)
        }
        link.setState(ConnectionState.Connected(adapterInfo))
        val poller = fakePoller(this, link)
        poller.start()
        poller.demand("screen", setOf("rpm"))
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()

        val polled = link.sent.filter { it !in DETECTION_COMMANDS }
        assertEquals(listOf("010C1", "010C"), polled.take(2))
        assertTrue(polled.drop(2).all { it == "010C" })
        assertEquals(1.0 * 0x1AF8 / 4, poller.readings.value.getValue("rpm").value, 0.001)
    }

    @Test
    fun `an adapter that rejects the response count falls back to plain commands once`() = runTest {
        val link = FakeLink { ecuAnswer(it, acceptsSuffix = false) }
        link.setState(ConnectionState.Connected(adapterInfo))
        val poller = fakePoller(this, link)
        poller.start()
        poller.demand("screen", setOf("rpm", "speed"))
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        val polled = link.sent.filter { it !in DETECTION_COMMANDS }
        assertEquals(listOf("010C1", "010C"), polled.take(2))
        assertEquals(1, polled.count { it.length == 5 })
        assertTrue(polled.drop(2).all { it == "010C" || it == "010D" })
        assertNotNull(poller.readings.value["speed"])
        assertEquals(PollMode.ACTIVE, poller.stats.value.mode)
    }

    @Test
    fun `the response count is tried again on a new connection`() = runTest {
        val link = FakeLink { ecuAnswer(it, acceptsSuffix = false) }
        link.setState(ConnectionState.Connected(adapterInfo))
        val poller = fakePoller(this, link)
        poller.start()
        poller.demand("screen", setOf("rpm"))
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, link.sent.count { it == "010C1" })

        link.setState(ConnectionState.Reconnecting(1, 1_000, "test"))
        advanceTimeBy(2_000)
        link.setState(ConnectionState.Connected(adapterInfo))
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(2, link.sent.count { it == "010C1" })
    }

    @Test
    fun `failed supported pid detection is retried later`() = runTest {
        var silent = true
        val link = FakeLink { if (silent) ElmResult.NoData("NO DATA") else ecuAnswer(it, acceptsSuffix = true) }
        link.setState(ConnectionState.Connected(adapterInfo))
        val poller = fakePoller(this, link)
        poller.start()
        poller.demand("screen", setOf("rpm"))
        advanceTimeBy(1_000)
        runCurrent()

        assertNull(poller.supportedPids.value)
        assertEquals(PollMode.WAITING, poller.stats.value.mode)
        assertTrue(link.log.snapshot().any { it.kind == LogKind.ERROR })

        silent = false
        advanceTimeBy(6_000)
        runCurrent()

        assertNotNull(poller.supportedPids.value)
        assertEquals(PollMode.ACTIVE, poller.stats.value.mode)
        assertNotNull(poller.readings.value["rpm"])
    }

    // ---------------------------------------------------------------- engine off

    @Test
    fun `engine off mode polls only rpm and returns to active when the engine runs`() = runTest {
        val rig = Rig(this, pollerConfig = PollerConfig(engineOffAfterMs = 10_000))
        rig.connect()
        rig.engine.setRunning(false, rig.clock())
        rig.poller.start()
        rig.poller.demand("screen", setOf("rpm", "coolant"))

        rig.elapse(5_000)
        assertEquals(PollMode.ACTIVE, rig.poller.stats.value.mode)
        rig.elapse(10_000)
        assertEquals(PollMode.ENGINE_OFF, rig.poller.stats.value.mode)

        rig.manager.log.clear()
        rig.elapse(30_000)
        assertEquals(setOf("010C"), rig.commands().toSet())
        assertTrue(rig.commands().size in 5..7)

        rig.engine.setRunning(true, rig.clock())
        rig.elapse(15_000)
        assertEquals(PollMode.ACTIVE, rig.poller.stats.value.mode)
        assertTrue("0105" in rig.commands())
    }

    @Test
    fun `no data from the ecu also counts as engine off`() = runTest {
        val rig = Rig(this, pollerConfig = PollerConfig(engineOffAfterMs = 10_000))
        rig.connect()
        rig.poller.start()
        rig.poller.demand("screen", setOf("coolant"))
        rig.elapse(1_000)
        rig.sim.randomNoDataRate = 1.0
        rig.elapse(25_000)

        assertEquals(PollMode.ENGINE_OFF, rig.poller.stats.value.mode)
        assertTrue(rig.poller.stats.value.unsupported.isEmpty())

        rig.sim.randomNoDataRate = 0.0
        rig.elapse(15_000)
        assertEquals(PollMode.ACTIVE, rig.poller.stats.value.mode)
    }

    @Test
    fun `rpm is probed at the check interval when nobody demanded it`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.poller.start()
        rig.poller.demand("screen", setOf("speed"))
        rig.elapse(30_000)

        val gaps = gapsOf(rig, "010C")
        assertTrue(gaps.isNotEmpty())
        assertTrue("rpm probe gaps $gaps", gaps.all { it in 5_000..6_500 })
    }

    // ---------------------------------------------------------------- pausing

    @Test
    fun `withPollingPaused gives exclusive access and polling resumes`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.poller.start()
        rig.poller.demand("screen", setOf("rpm", "speed"))
        rig.elapse(2_000)

        var start = 0
        var end = 0
        val result = rig.poller.withPollingPaused { link ->
            start = rig.manager.log.snapshot().size
            link.execute("ATRV")
            delay(3_000)
            link.execute("0105")
            end = rig.manager.log.snapshot().size
            "done"
        }
        assertEquals("done", result)

        val during = rig.manager.log.snapshot().subList(start, end).filter { it.kind == LogKind.TX }.map { it.text }
        assertEquals(listOf("ATRV", "0105"), during)

        val sizeAtEnd = rig.manager.log.snapshot().size
        rig.elapse(2_000)
        assertTrue(rig.manager.log.snapshot().size > sizeAtEnd)
    }

    @Test
    fun `polling resumes when the paused block throws`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.poller.start()
        rig.poller.demand("screen", setOf("speed"))
        rig.elapse(1_000)

        assertFails<IllegalArgumentException> {
            rig.poller.withPollingPaused<Unit> { throw IllegalArgumentException("boom") }
        }
        val before = rig.commands().size
        rig.elapse(2_000)

        assertTrue(rig.commands().size > before)
    }

    // ---------------------------------------------------------------- link lifecycle

    @Test
    fun `waits for the connection before sending anything`() = runTest {
        val rig = Rig(this)
        rig.poller.start()
        rig.poller.demand("screen", setOf("rpm"))
        rig.elapse(5_000)
        assertEquals(PollMode.WAITING, rig.poller.stats.value.mode)
        assertNull(rig.poller.supportedPids.value)

        rig.connect()
        rig.elapse(3_000)
        assertEquals(PollMode.ACTIVE, rig.poller.stats.value.mode)
        assertTrue("010C" in rig.commands())
    }

    @Test
    fun `a link drop goes to waiting and polling resumes after the reconnect`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.poller.start()
        rig.poller.demand("screen", setOf("rpm", "speed"))
        rig.elapse(3_000)
        assertEquals(PollMode.ACTIVE, rig.poller.stats.value.mode)

        rig.sim.dropConnectionAfterCommands = 0
        rig.elapse(500)
        assertEquals(PollMode.WAITING, rig.poller.stats.value.mode)
        assertNull(rig.poller.supportedPids.value)

        val droppedAt = rig.clock()
        rig.elapse(30_000)
        assertEquals(PollMode.ACTIVE, rig.poller.stats.value.mode)
        assertNotNull(rig.poller.supportedPids.value)
        assertTrue(rig.poller.readings.value.getValue("speed").timestampMs > droppedAt)
    }

    @Test
    fun `leaving the connected state without any request also goes to waiting`() = runTest {
        val link = FakeLink { ecuAnswer(it, acceptsSuffix = true) }
        link.setState(ConnectionState.Connected(adapterInfo))
        val poller = fakePoller(this, link)
        poller.start()
        advanceTimeBy(1_000)
        runCurrent()
        assertNotNull(poller.supportedPids.value)

        link.setState(ConnectionState.Reconnecting(1, 1_000, "test"))
        runCurrent()
        assertNull(poller.supportedPids.value)
        assertEquals(PollMode.WAITING, poller.stats.value.mode)

        link.setState(ConnectionState.Connected(adapterInfo))
        advanceTimeBy(2_000)
        runCurrent()
        assertNotNull(poller.supportedPids.value)
    }

    @Test
    fun `stop ends polling and reports waiting`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.poller.start()
        rig.poller.demand("screen", setOf("rpm"))
        rig.elapse(3_000)

        rig.poller.stop()
        val size = rig.manager.log.snapshot().size
        rig.elapse(10_000)

        assertEquals(size, rig.manager.log.snapshot().size)
        assertEquals(PollMode.WAITING, rig.poller.stats.value.mode)
        assertNull(rig.poller.supportedPids.value)
    }

    // ---------------------------------------------------------------- stats

    @Test
    fun `stats report throughput and per signal rates while polling`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.poller.start()
        rig.poller.demand("screen", setOf("rpm", "speed"))
        rig.elapse(10_000)

        val stats = rig.poller.stats.value
        assertEquals(PollMode.ACTIVE, stats.mode)
        assertTrue(stats.requestsPerSecond > 1.0)
        assertTrue(stats.signalHz.getValue("rpm") > 0.5)
        assertFalse("coolant" in stats.signalHz)
    }

    private companion object {
        const val FAKE_LATENCY_MS = 100L
        val DETECTION_COMMANDS = setOf("0100", "0120", "0140", "0160", "0180", "01A0", "01C0", "01E0")
    }
}
