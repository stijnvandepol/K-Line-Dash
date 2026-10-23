package nl.stijnvdpol.klinedash.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.car.CarMake
import nl.stijnvdpol.klinedash.core.elm.AdapterInfo
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.elm.ElmLink
import nl.stijnvdpol.klinedash.core.elm.ElmResult
import nl.stijnvdpol.klinedash.core.log.CommLog
import nl.stijnvdpol.klinedash.core.log.RingCommLog
import nl.stijnvdpol.klinedash.core.obd.ObdPoller
import nl.stijnvdpol.klinedash.core.obd.PollStats
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.data.CarSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VinDetectorTest {
    private val volkswagenVin = "WVWZZZ1JZXW000001"
    private val toyotaVin = "SB1ZE22E10E123456"
    private val repository = CarSettingsRepository(FakeSettingsDao(), Dispatchers.Unconfined)
    private val detector = VinDetector(repository)

    /** The link of a car that answers [reply]; the state is moved by the test. */
    private class ControlledLink(private val reply: (String) -> ElmResult) : ElmLink {
        override val state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override val log: CommLog = RingCommLog()
        val sent = ArrayList<String>()

        override fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun forgetSavedProtocol() = Unit

        override suspend fun execute(command: String, timeoutMs: Long): ElmResult {
            sent += command
            return reply(command)
        }

        fun connect() {
            state.value = ConnectionState.Connected(AdapterInfo("ELM327 v1.5", "3", "ISO 9141-2", 12.4, false, emptySet()))
        }

        fun drop() {
            state.value = ConnectionState.Reconnecting(1, 1_000, "test")
        }
    }

    /** Hands the link over like the real poller does; [supportedPids] stays null until the test says the sensors are known. */
    private class HandOverPoller(private val link: ElmLink) : ObdPoller {
        override val readings = MutableStateFlow<Map<String, Reading>>(emptyMap())
        override val supportedPids = MutableStateFlow<Set<Int>?>(null)
        override val stats: StateFlow<PollStats> = MutableStateFlow(PollStats())
        var handOvers = 0

        override fun demand(owner: String, signalIds: Set<String>) = Unit
        override fun release(owner: String) = Unit
        override fun start() = Unit
        override suspend fun stop() = Unit

        override suspend fun <T> withPollingPaused(block: suspend (ElmLink) -> T): T {
            handOvers++
            return block(link)
        }
    }

    /** The K-line layout of a VIN answer: counter byte, four data bytes per line, the VIN behind three zeros. */
    private fun vinAnswer(vin: String): ElmResult.Data {
        val bytes = listOf(0, 0, 0) + vin.map { it.code }
        val lines = bytes.chunked(4).mapIndexed { index, chunk ->
            "4902%02X".format(index + 1) + chunk.joinToString("") { "%02X".format(it) }.padEnd(8, '0')
        }
        return ElmResult.Data(lines, lines.joinToString("\r"))
    }

    private fun carWithVin(vin: String) = ControlledLink { vinAnswer(vin) }

    private fun TestScope.startDetector(adapterKey: String, link: ControlledLink, poller: HandOverPoller) {
        backgroundScope.launch { detector.run(adapterKey, link, poller) }
        runCurrent()
    }

    private suspend fun detectedVin() = repository.settings.first().detectedVin

    @Test
    fun `nothing is asked before the sensors are known, then the VIN is read and stored`() = runTest {
        val link = carWithVin(volkswagenVin)
        val poller = HandOverPoller(link)
        startDetector("bt:1", link, poller)

        link.connect()
        runCurrent()
        assertEquals(emptyList<String>(), link.sent)

        poller.supportedPids.value = setOf(0x0C)
        runCurrent()
        assertEquals(listOf("0902"), link.sent)
        assertEquals(volkswagenVin, detectedVin())
        assertEquals(CarMake.VW_GROUP, repository.settings.first().effectiveMake)
    }

    @Test
    fun `nothing is asked while there is no connection`() = runTest {
        val link = carWithVin(volkswagenVin)
        val poller = HandOverPoller(link)
        poller.supportedPids.value = setOf(0x0C)
        startDetector("bt:1", link, poller)

        assertEquals(0, poller.handOvers)
    }

    @Test
    fun `the request goes through the poller's hand-over so it never interleaves with polling`() = runTest {
        val link = carWithVin(volkswagenVin)
        val poller = HandOverPoller(link).also { it.supportedPids.value = setOf(0x0C) }
        startDetector("bt:1", link, poller)

        link.connect()
        runCurrent()

        assertEquals(1, poller.handOvers)
    }

    @Test
    fun `a reconnect of an adapter that gave its VIN does not ask again`() = runTest {
        val link = carWithVin(volkswagenVin)
        val poller = HandOverPoller(link).also { it.supportedPids.value = setOf(0x0C) }
        startDetector("bt:1", link, poller)

        link.connect()
        runCurrent()
        link.drop()
        runCurrent()
        link.connect()
        runCurrent()

        assertEquals(listOf("0902"), link.sent)
    }

    @Test
    fun `a car that has no VIN is asked once, not on every connect, and an earlier detection goes`() = runTest {
        repository.recordVin(toyotaVin)
        val link = ControlledLink { ElmResult.NoData("NO DATA") }
        val poller = HandOverPoller(link).also { it.supportedPids.value = setOf(0x0C) }
        startDetector("bt:1", link, poller)

        link.connect()
        runCurrent()
        assertNull(detectedVin())
        link.drop()
        runCurrent()
        link.connect()
        runCurrent()

        assertEquals(listOf("0902"), link.sent)
    }

    @Test
    fun `the memory is kept for the adapter, so a new link to the same adapter does not ask either`() = runTest {
        val first = ControlledLink { ElmResult.NoData("NO DATA") }
        val firstPoller = HandOverPoller(first).also { it.supportedPids.value = setOf(0x0C) }
        val firstRun = backgroundScope.launch { detector.run("bt:1", first, firstPoller) }
        runCurrent()
        first.connect()
        runCurrent()
        firstRun.cancel()

        val second = ControlledLink { ElmResult.NoData("NO DATA") }
        startDetector("bt:1", second, HandOverPoller(second).also { it.supportedPids.value = setOf(0x0C) })
        second.connect()
        runCurrent()

        assertEquals(listOf("0902"), first.sent)
        assertEquals(emptyList<String>(), second.sent)
    }

    @Test
    fun `another adapter is asked again`() = runTest {
        val first = ControlledLink { ElmResult.NoData("NO DATA") }
        val firstRun = backgroundScope.launch {
            detector.run("bt:1", first, HandOverPoller(first).also { it.supportedPids.value = setOf(0x0C) })
        }
        runCurrent()
        first.connect()
        runCurrent()
        firstRun.cancel()

        val second = carWithVin(toyotaVin)
        startDetector("bt:2", second, HandOverPoller(second).also { it.supportedPids.value = setOf(0x0C) })
        second.connect()
        runCurrent()

        assertEquals(listOf("0902"), second.sent)
        assertEquals(toyotaVin, detectedVin())
    }

    @Test
    fun `a lost answer is not an answer, the next connection asks again`() = runTest {
        var isLost = true
        val link = ControlledLink { if (isLost) ElmResult.Timeout("") else vinAnswer(toyotaVin) }
        val poller = HandOverPoller(link).also { it.supportedPids.value = setOf(0x0C) }
        startDetector("bt:1", link, poller)

        link.connect()
        runCurrent()
        assertNull(detectedVin())

        isLost = false
        link.drop()
        runCurrent()
        link.connect()
        runCurrent()

        assertEquals(toyotaVin, detectedVin())
    }

    @Test
    fun `a link that drops under the request is survived and asked again on the next connection`() = runTest {
        var isDown = true
        val link = ControlledLink { if (isDown) throw IllegalStateException("Not connected to the adapter.") else vinAnswer(toyotaVin) }
        val poller = HandOverPoller(link).also { it.supportedPids.value = setOf(0x0C) }
        startDetector("bt:1", link, poller)

        link.connect()
        runCurrent()
        assertNull(detectedVin())

        isDown = false
        link.drop()
        runCurrent()
        link.connect()
        runCurrent()

        assertEquals(toyotaVin, detectedVin())
    }

    @Test
    fun `a VIN that differs from the stored one replaces the detection`() = runTest {
        repository.recordVin(volkswagenVin)
        val link = carWithVin(toyotaVin)
        startDetector("bt:1", link, HandOverPoller(link).also { it.supportedPids.value = setOf(0x0C) })

        link.connect()
        runCurrent()

        assertEquals(CarMake.TOYOTA_LEXUS, repository.settings.first().effectiveMake)
    }
}
