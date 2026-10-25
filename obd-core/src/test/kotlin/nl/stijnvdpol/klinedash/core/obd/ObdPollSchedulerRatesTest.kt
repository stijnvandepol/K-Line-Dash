package nl.stijnvdpol.klinedash.core.obd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.elm.AdapterInfo
import nl.stijnvdpol.klinedash.core.elm.ElmResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How often each signal is updated on a K-line car with a clone adapter, measured with the real
 * scheduler on virtual time. The time per request is the limit: at 0.5 s the bus carries two
 * requests a second, and the plan decides who gets them ([planRound]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObdPollSchedulerRatesTest {

    private val dashboard = setOf("rpm", "speed", "throttle", "coolant", "intake_temp", "load", "timing", "maf", "battery")
    private val fastOnly = setOf("rpm", "speed", "throttle")

    private fun TestScope.pollerOn(link: TimedLink) = ObdPollScheduler(
        link, backgroundScope, Signals.all, { testScheduler.currentTime },
        monotonicClock = { testScheduler.currentTime },
    )

    /** Update rate in Hz per command over the measuring window, after the detection and the first rounds. */
    private class Rates(private val requests: List<SentRequest>) {
        private val windowSeconds = (RUN_MS - WARM_UP_MS) / 1_000.0

        fun hz(command: String): Double = requests.count { it.base == command && it.timeMs >= WARM_UP_MS } / windowSeconds
    }

    private fun TestScope.measure(
        info: AdapterInfo,
        latencyMs: Long,
        demand: Set<String>,
        scripted: (SentRequest) -> ElmResult? = { null },
    ): Pair<TimedLink, Rates> {
        val link = TimedLink({ testScheduler.currentTime }, info, latencyMs, scripted = scripted)
        val poller = pollerOn(link)
        poller.start()
        poller.demand("screen", demand)
        advanceTimeBy(RUN_MS)
        runCurrent()
        return link to Rates(link.polled())
    }

    @Test
    fun `at half a second per request the dashboard gets rpm every second and speed every two`() = runTest {
        val (_, rates) = measure(K_LINE_INFO, latencyMs = 500, demand = dashboard)

        assertEquals(1.0, rates.hz("010C"), 0.1)
        assertEquals(0.5, rates.hz("010D"), 0.05)
        // Throttle gets every third slot; coolant, load, timing, intake temperature and MAF share the rest.
        assertEquals(0.17, rates.hz("0111"), 0.04)
        assertTrue("coolant ${rates.hz("0105")} Hz", rates.hz("0105") > 0.055)
    }

    @Test
    fun `with nothing else demanded throttle takes every free slot`() = runTest {
        val (_, rates) = measure(K_LINE_INFO, latencyMs = 500, demand = fastOnly)

        assertEquals(1.0, rates.hz("010C"), 0.05)
        assertEquals(0.5, rates.hz("010D"), 0.05)
        assertEquals(0.5, rates.hz("0111"), 0.05)
    }

    @Test
    fun `at 0_3 seconds per request rpm is polled well over once a second`() = runTest {
        val (_, rates) = measure(K_LINE_INFO, latencyMs = 300, demand = dashboard)

        assertTrue("rpm ${rates.hz("010C")} Hz", rates.hz("010C") > 1.5)
        assertEquals(0.85, rates.hz("010D"), 0.1)
        assertTrue("throttle ${rates.hz("0111")} Hz", rates.hz("0111") > 0.25)
        assertTrue("coolant ${rates.hz("0105")} Hz", rates.hz("0105") > 0.04)
    }

    @Test
    fun `an rpm that nobody demanded is not sent twice and the others stay one per round`() = runTest {
        val (link, rates) = measure(K_LINE_INFO, latencyMs = 500, demand = setOf("speed", "throttle", "coolant"))

        val commands = link.polled().map { it.base }
        assertTrue(commands.zipWithNext().none { (a, b) -> a == b })
        assertEquals(rates.hz("010D"), rates.hz("0111"), 0.05)
        // Only the engine-off probe, due every 5 s and sent in the next free slot.
        assertTrue("rpm probe ${rates.hz("010C")} Hz", rates.hz("010C") in 0.1..0.2)
    }

    @Test
    fun `rpm alone is not sent twice in a row`() = runTest {
        val (link, rates) = measure(K_LINE_INFO, latencyMs = 500, demand = setOf("rpm"))

        assertTrue(link.polled().map { it.base }.all { it == "010C" })
        assertEquals(2.0, rates.hz("010C"), 0.1)
    }

    @Test
    fun `a can car that batches keeps one request for rpm and speed together`() = runTest {
        val (link, rates) = measure(CAN_INFO, latencyMs = 100, demand = fastOnly)

        val settled = link.polled(WARM_UP_MS)
        assertTrue(settled.any { it.base == "010C0D" })
        assertTrue(settled.none { it.base == "010C" })
        assertEquals(rates.hz("010C0D"), rates.hz("0111"), 0.1)
    }

    @Test
    fun `the poller publishes the mean round trip of answered requests`() = runTest {
        val link = TimedLink({ testScheduler.currentTime }, K_LINE_INFO, latencyMs = 250)
        val poller = pollerOn(link)
        poller.start()
        poller.demand("screen", fastOnly)
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals(250.0, poller.stats.value.meanRoundTripMs, 1.0)
    }

    @Test
    fun `timeouts do not count towards the mean round trip`() = runTest {
        val link = TimedLink(
            { testScheduler.currentTime }, K_LINE_INFO, latencyMs = 100,
            scripted = { request -> if (request.base in TimedLink.DETECTION_COMMANDS) null else ElmResult.Timeout("") },
        )
        val poller = pollerOn(link)
        poller.start()
        poller.demand("screen", fastOnly)
        advanceTimeBy(10_000)
        runCurrent()

        assertTrue(poller.stats.value.requestsPerSecond > 0.0)
        assertEquals(0.0, poller.stats.value.meanRoundTripMs, 0.0)
    }

    private companion object {
        const val RUN_MS = 130_000L
        const val WARM_UP_MS = 10_000L
    }
}
