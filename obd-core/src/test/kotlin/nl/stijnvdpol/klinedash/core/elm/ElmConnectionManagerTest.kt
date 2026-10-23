package nl.stijnvdpol.klinedash.core.elm

import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import nl.stijnvdpol.klinedash.core.assertFails
import nl.stijnvdpol.klinedash.core.assertIs
import nl.stijnvdpol.klinedash.core.car.CarMake
import nl.stijnvdpol.klinedash.core.log.LogKind
import nl.stijnvdpol.klinedash.core.sim.SimulatorConfig
import nl.stijnvdpol.klinedash.core.sim.SimulatorTransport
import nl.stijnvdpol.klinedash.core.transport.LOCKED_ADAPTER_MESSAGE
import nl.stijnvdpol.klinedash.core.transport.Transport
import nl.stijnvdpol.klinedash.core.transport.TransportException
import nl.stijnvdpol.klinedash.core.transport.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ElmConnectionManagerTest {

    private class FakeProtocolStore(initial: Map<String, String> = emptyMap()) : ProtocolStore {
        val saved = HashMap<String, String>(initial)
        var saveCalls = 0

        override suspend fun loadProtocol(adapterKey: String): String? = saved[adapterKey]

        override suspend fun saveProtocol(adapterKey: String, protocolNumber: String) {
            saveCalls++
            saved[adapterKey] = protocolNumber
        }

        override suspend fun clearProtocol(adapterKey: String) {
            saved.remove(adapterKey)
        }
    }

    /** Stands in for a Bluetooth transport on Android 12+ without BLUETOOTH_CONNECT. */
    private class DeniedTransport : Transport {
        override val kind = TransportKind.BT_CLASSIC
        override val description = "denied"
        override val isConnected = false

        override suspend fun connect() {
            throw SecurityException("BLUETOOTH_CONNECT not granted")
        }

        override suspend fun write(data: String) = Unit
        override suspend fun readUntilPrompt(timeoutMs: Long): String = ""
        override fun discardPending() = Unit
        override suspend fun close() = Unit
    }

    // ---- connection lifecycle ----

    @Test
    fun `connects walks through the states and stores the detected protocol`() = runTest {
        val store = FakeProtocolStore()
        val manager = ElmConnectionManager({ SimulatorTransport(SimulatorConfig()) }, "sim", store, backgroundScope)
        val seen = ArrayList<ConnectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { manager.state.collect { seen.add(it) } }

        assertEquals(ConnectionState.Idle, manager.state.value)
        manager.start()
        val connected = withTimeout(60_000) { manager.state.first { it is ConnectionState.Connected } }

        val info = assertIs<ConnectionState.Connected>(connected).info
        assertEquals("3", info.protocolNumber)
        assertFalse(info.usedSavedProtocol)
        assertEquals("3", store.saved["sim"])
        assertTrue(seen.first() is ConnectionState.Idle)
        assertTrue(seen.any { it is ConnectionState.Connecting })
        assertTrue(seen.any { it is ConnectionState.Initializing })
        assertTrue(seen.last() is ConnectionState.Connected)
        assertTrue(manager.log.snapshot().isNotEmpty())
    }

    @Test
    fun `saved protocol is used and not saved again`() = runTest {
        val store = FakeProtocolStore(mapOf("sim" to "3"))
        val manager = ElmConnectionManager({ SimulatorTransport(SimulatorConfig()) }, "sim", store, backgroundScope)
        manager.start()
        val connected = assertIs<ConnectionState.Connected>(
            withTimeout(60_000) { manager.state.first { it is ConnectionState.Connected } },
        )
        assertTrue(connected.info.usedSavedProtocol)
        assertEquals(0, store.saveCalls)
    }

    @Test
    fun `auto protocol from an unreadable atdpn is not stored`() = runTest {
        val config = SimulatorConfig().apply { cloneRejectsAt.add("ATDPN") }
        val store = FakeProtocolStore()
        val manager = ElmConnectionManager({ SimulatorTransport(config) }, "sim", store, backgroundScope)
        manager.start()
        withTimeout(60_000) { manager.state.first { it is ConnectionState.Connected } }
        assertEquals(0, store.saveCalls)
        assertNull(store.saved["sim"])
    }

    @Test
    fun `start is a no-op while already running`() = runTest {
        var created = 0
        val manager = ElmConnectionManager(
            { created++; SimulatorTransport(SimulatorConfig()) },
            "sim", FakeProtocolStore(), backgroundScope,
        )
        manager.start()
        manager.start()
        withTimeout(60_000) { manager.state.first { it is ConnectionState.Connected } }
        manager.start()
        runCurrent()
        assertEquals(1, created)
    }

    @Test
    fun `execute before connecting throws illegal state`() = runTest {
        val manager = ElmConnectionManager({ SimulatorTransport() }, "sim", FakeProtocolStore(), backgroundScope)
        assertFails<IllegalStateException> { manager.execute("010C") }
    }

    @Test
    fun `execute returns parsed data once connected`() = runTest {
        val manager = ElmConnectionManager({ SimulatorTransport() }, "sim", FakeProtocolStore(), backgroundScope)
        manager.start()
        withTimeout(60_000) { manager.state.first { it is ConnectionState.Connected } }
        val data = assertIs<ElmResult.Data>(manager.execute("010C"))
        assertTrue(data.lines[0].startsWith("410C"))
    }

    @Test
    fun `factory that throws ends in failed instead of crashing`() = runTest {
        val manager = ElmConnectionManager(
            { throw TransportException("Geen adapter gekozen") }, "bt:", FakeProtocolStore(), backgroundScope,
        )
        manager.start()
        val state = withTimeout(5_000) { manager.state.first { it is ConnectionState.Failed } }
        assertEquals("Geen adapter gekozen", assertIs<ConnectionState.Failed>(state).reason)
    }

    @Test
    fun `adapter that never answers atz ends in failed and is not retried`() = runTest {
        var created = 0
        val config = SimulatorConfig().apply { timeoutRate = 1.0 }
        val manager = ElmConnectionManager(
            { created++; SimulatorTransport(config) }, "sim", FakeProtocolStore(), backgroundScope,
        )
        manager.start()
        val failed = assertIs<ConnectionState.Failed>(
            withTimeout(60_000) { manager.state.first { it is ConnectionState.Failed } },
        )

        assertEquals(LOCKED_ADAPTER_MESSAGE, failed.reason)
        assertEquals(FailureKind.UNSUPPORTED_ADAPTER, failed.kind)
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(1, created)
        assertTrue(manager.state.value is ConnectionState.Failed)
    }

    @Test
    fun `an adapter with a saved protocol that is silent at start is retried`() = runTest {
        var created = 0
        val config = SimulatorConfig().apply { timeoutRate = 1.0 }
        val manager = ElmConnectionManager(
            { created++; SimulatorTransport(config) }, "sim", FakeProtocolStore(mapOf("sim" to "3")), backgroundScope,
            random = Random(1),
        )
        manager.start()
        val reconnecting = assertIs<ConnectionState.Reconnecting>(
            withTimeout(60_000) { manager.state.first { it is ConnectionState.Reconnecting || it is ConnectionState.Failed } },
        )
        assertEquals(LOCKED_ADAPTER_MESSAGE, reconnecting.reason)

        config.timeoutRate = 0.0
        withTimeout(120_000) { manager.state.first { it is ConnectionState.Connected } }
        assertTrue(created >= 2)
    }

    @Test
    fun `silence after an adapter has worked is retried like any drop`() = runTest {
        var created = 0
        val config = SimulatorConfig()
        val manager = ElmConnectionManager(
            { created++; SimulatorTransport(config) }, "sim", FakeProtocolStore(), backgroundScope,
            random = Random(1),
        )
        manager.start()
        withTimeout(60_000) { manager.state.first { it is ConnectionState.Connected } }

        config.timeoutRate = 1.0
        config.dropConnectionAfterCommands = 0
        assertFails<TransportException> { manager.execute("ATI") }
        val reconnecting = assertIs<ConnectionState.Reconnecting>(
            withTimeout(60_000) { manager.state.first { it is ConnectionState.Reconnecting } },
        )
        assertTrue(reconnecting.reason.isNotBlank())

        advanceTimeBy(120_000L)
        runCurrent()
        assertTrue("created $created", created >= 3)
        assertFalse(manager.state.value is ConnectionState.Failed)
    }

    @Test
    fun `failure kind defaults to other`() {
        assertEquals(FailureKind.OTHER, ConnectionState.Failed("x").kind)
    }

    @Test
    fun `a drop while idle is noticed without sending a command`() = runTest {
        var sim: SimulatorTransport? = null
        val manager = ElmConnectionManager(
            { SimulatorTransport().also { sim = it } }, "sim", FakeProtocolStore(), backgroundScope,
        )
        manager.start()
        withTimeout(60_000) { manager.state.first { it is ConnectionState.Connected } }

        sim!!.close() // stands in for Bluetooth going away underneath us
        val state = withTimeout(5_000) { manager.state.first { it is ConnectionState.Reconnecting } }
        assertTrue(assertIs<ConnectionState.Reconnecting>(state).reason.contains("dropped"))
    }

    @Test
    fun `three timeouts in a row force a reconnect`() = runTest {
        val config = SimulatorConfig()
        val manager = ElmConnectionManager({ SimulatorTransport(config) }, "sim", FakeProtocolStore(), backgroundScope)
        manager.start()
        withTimeout(60_000) { manager.state.first { it is ConnectionState.Connected } }

        config.timeoutRate = 1.0
        repeat(3) { assertIs<ElmResult.Timeout>(manager.execute("010C", 500)) }
        assertFails<IllegalStateException> { manager.execute("010C", 500) }
        withTimeout(5_000) { manager.state.first { it is ConnectionState.Reconnecting } }
    }

    @Test
    fun `commands are serialised`() = runTest {
        val manager = ElmConnectionManager({ SimulatorTransport() }, "sim", FakeProtocolStore(), backgroundScope)
        manager.start()
        withTimeout(60_000) { manager.state.first { it is ConnectionState.Connected } }

        // The simulator throws if a command is written while another one is still in flight.
        val a = async { manager.execute("010C") }
        val b = async { manager.execute("010D") }
        assertTrue(assertIs<ElmResult.Data>(a.await()).lines[0].startsWith("410C"))
        assertTrue(assertIs<ElmResult.Data>(b.await()).lines[0].startsWith("410D"))
    }

    @Test
    fun `forget saved protocol clears the store`() = runTest {
        val store = FakeProtocolStore(mapOf("sim" to "3"))
        val manager = ElmConnectionManager({ SimulatorTransport() }, "sim", store, backgroundScope)
        manager.forgetSavedProtocol()
        assertNull(store.saved["sim"])
    }

    // ---- reconnect ----

    @Test
    fun `dropped link triggers reconnect that reuses the saved protocol`() = runTest {
        val config = SimulatorConfig().apply { dropConnectionAfterCommands = 20 }
        val store = FakeProtocolStore()
        var created = 0
        val manager = ElmConnectionManager(
            { created++; SimulatorTransport(config) }, "sim", store, backgroundScope, random = Random(1),
        )
        manager.start()
        withTimeout(60_000) { manager.state.first { it is ConnectionState.Connected } }
        assertEquals(1, created)

        // Init used 13 commands, so the drop hits on the 8th OBD request.
        assertFails<TransportException> { repeat(20) { manager.execute("0100") } }
        assertFails<IllegalStateException> { manager.execute("0100") }

        val reconnecting = assertIs<ConnectionState.Reconnecting>(
            withTimeout(60_000) { manager.state.first { it is ConnectionState.Reconnecting } },
        )
        assertEquals(1, reconnecting.attempt)
        assertTrue(reconnecting.delayMs in 800L..1_200L)
        assertTrue(reconnecting.reason.isNotBlank())

        val again = assertIs<ConnectionState.Connected>(
            withTimeout(60_000) { manager.state.first { it is ConnectionState.Connected } },
        )
        assertEquals(2, created)
        assertTrue(again.info.usedSavedProtocol)
        assertTrue(manager.execute("0100") is ElmResult.Data)
    }

    @Test
    fun `backoff grows per failed attempt and resets after a success`() = runTest {
        val config = SimulatorConfig()
        var created = 0
        val manager = ElmConnectionManager(
            { created++; config.failConnect = created <= 3; SimulatorTransport(config) },
            "sim", FakeProtocolStore(), backgroundScope,
            backoff = BackoffPolicy(jitter = 0.0),
        )
        val reconnects = ArrayList<ConnectionState.Reconnecting>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.state.collect { if (it is ConnectionState.Reconnecting) reconnects.add(it) }
        }

        manager.start()
        withTimeout(120_000) { manager.state.first { it is ConnectionState.Connected } }

        assertEquals(listOf(1, 2, 3), reconnects.map { it.attempt })
        assertEquals(listOf(1_000L, 2_000L, 4_000L), reconnects.map { it.delayMs })
        assertTrue(testScheduler.currentTime >= 7_000)
        assertEquals(4, created)

        // A later drop starts again at attempt 1.
        config.dropConnectionAfterCommands = 0
        assertFails<TransportException> { manager.execute("ATI") }
        val next = assertIs<ConnectionState.Reconnecting>(
            withTimeout(60_000) { manager.state.first { it is ConnectionState.Reconnecting } },
        )
        assertEquals(1, next.attempt)
        assertEquals(1_000L, next.delayMs)
    }

    @Test
    fun `silent ecu keeps retrying and recovers when it answers`() = runTest {
        val config = SimulatorConfig().apply { initFails = true }
        val manager = ElmConnectionManager({ SimulatorTransport(config) }, "sim", FakeProtocolStore(), backgroundScope)
        manager.start()

        val first = assertIs<ConnectionState.Reconnecting>(
            withTimeout(120_000) { manager.state.first { it is ConnectionState.Reconnecting } },
        )
        assertTrue(first.reason.contains("ECU"))

        config.initFails = false // ignition turned on
        assertIs<ConnectionState.Connected>(
            withTimeout(300_000) { manager.state.first { it is ConnectionState.Connected } },
        )
    }

    @Test
    fun `make hint is read again on every connection attempt`() = runTest {
        val config = SimulatorConfig().apply { initFails = true }
        var make: CarMake? = null
        var hintReads = 0
        val manager = ElmConnectionManager(
            { SimulatorTransport(config) }, "sim", FakeProtocolStore(), backgroundScope,
            makeHint = { hintReads++; make },
        )
        manager.start()
        withTimeout(120_000) { manager.state.first { it is ConnectionState.Reconnecting } }
        assertEquals(1, hintReads)

        // The user picks Renault; the car is a KWP fast-init car whose clone cannot auto-search.
        make = CarMake.RENAULT_DACIA
        config.initFails = false
        config.autoDetectFails = true
        config.carProtocol = "5"
        val connected = assertIs<ConnectionState.Connected>(
            withTimeout(300_000) { manager.state.first { it is ConnectionState.Connected } },
        )

        assertEquals("5", connected.info.protocolNumber)
        assertTrue(hintReads >= 2)
        val sent = manager.log.snapshot().filter { it.kind == LogKind.TX }.map { it.text }
        val lastAttempt = sent.drop(sent.lastIndexOf("ATZ"))
        assertEquals(listOf("ATSP5"), lastAttempt.filter { it.startsWith("ATSP") && it != "ATSP0" })
    }

    @Test
    fun `security exception on connect fails without retrying`() = runTest {
        var created = 0
        val manager = ElmConnectionManager({ created++; DeniedTransport() }, "bt", FakeProtocolStore(), backgroundScope)
        manager.start()

        val failed = assertIs<ConnectionState.Failed>(
            withTimeout(60_000) { manager.state.first { it is ConnectionState.Failed } },
        )
        assertTrue(failed.reason.contains("BLUETOOTH_CONNECT"))

        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, created)
        assertTrue(manager.state.value is ConnectionState.Failed)
    }

    // ---- stop ----

    @Test
    fun `stop closes the transport and goes idle`() = runTest {
        var sim: SimulatorTransport? = null
        val manager = ElmConnectionManager(
            { SimulatorTransport().also { sim = it } },
            "sim", FakeProtocolStore(), backgroundScope,
        )
        manager.start()
        withTimeout(60_000) { manager.state.first { it is ConnectionState.Connected } }
        assertTrue(sim!!.isConnected)

        manager.stop()

        assertEquals(ConnectionState.Idle, manager.state.value)
        assertFalse(sim!!.isConnected)
        assertFails<IllegalStateException> { manager.execute("ATI") }
    }

    @Test
    fun `stop while waiting to reconnect cancels further attempts`() = runTest {
        val config = SimulatorConfig().apply { initFails = true }
        var created = 0
        val manager = ElmConnectionManager({ created++; SimulatorTransport(config) }, "sim", FakeProtocolStore(), backgroundScope)
        manager.start()
        withTimeout(120_000) { manager.state.first { it is ConnectionState.Reconnecting } }
        val attemptsBeforeStop = created

        manager.stop()
        advanceTimeBy(300_000)
        runCurrent()

        assertEquals(ConnectionState.Idle, manager.state.value)
        assertEquals(attemptsBeforeStop, created)
    }

    @Test
    fun `can be started again after stop`() = runTest {
        val manager = ElmConnectionManager({ SimulatorTransport() }, "sim", FakeProtocolStore(), backgroundScope)
        manager.start()
        withTimeout(60_000) { manager.state.first { it is ConnectionState.Connected } }
        manager.stop()
        manager.start()
        withTimeout(60_000) { manager.state.first { it is ConnectionState.Connected } }
        assertTrue(manager.execute("0100") is ElmResult.Data)
    }

    // ---- BackoffPolicy ----

    @Test
    fun `backoff doubles up to the cap without jitter`() {
        val policy = BackoffPolicy(initialMs = 1_000, maxMs = 30_000, factor = 2.0, jitter = 0.0)
        val random = Random(1)
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (1..7).map { policy.delayFor(it, random) },
        )
    }

    @Test
    fun `backoff attempt below one uses the initial delay`() {
        val policy = BackoffPolicy(jitter = 0.0)
        assertEquals(1_000L, policy.delayFor(0, Random(1)))
        assertEquals(1_000L, policy.delayFor(-5, Random(1)))
    }

    @Test
    fun `backoff jitter stays within the configured spread`() {
        val policy = BackoffPolicy(initialMs = 1_000, maxMs = 30_000, factor = 2.0, jitter = 0.2)
        val random = Random(42)
        repeat(500) {
            val d = policy.delayFor(3, random) // base 4000
            assertTrue("delay $d", d in 3_200L..4_800L)
        }
    }

    @Test
    fun `backoff never exceeds the cap even for huge attempt numbers`() {
        val policy = BackoffPolicy()
        val random = Random(7)
        for (attempt in listOf(10, 50, 1_000, 100_000)) {
            repeat(50) { assertTrue(policy.delayFor(attempt, random) <= 30_000L) }
        }
    }
}
