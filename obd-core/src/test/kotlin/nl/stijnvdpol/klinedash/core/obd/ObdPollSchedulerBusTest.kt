package nl.stijnvdpol.klinedash.core.obd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.elm.AdapterInfo
import nl.stijnvdpol.klinedash.core.elm.ElmResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The poller against a bus that misbehaves: no ECU behind the adapter, a K-line bus that went to
 * sleep, the supported PIDs the connect already knows, and transient errors on the first requests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObdPollSchedulerBusTest {

    private fun TestScope.pollerOn(link: TimedLink) = ObdPollScheduler(
        link, backgroundScope, Signals.all, { testScheduler.currentTime },
        monotonicClock = { testScheduler.currentTime },
    )

    private suspend fun TestScope.elapse(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun isPoll(request: SentRequest) = request.base !in TimedLink.DETECTION_COMMANDS

    // ---------------------------------------------------------------- no ECU

    private val busFailures = mapOf<String, (SentRequest) -> ElmResult>(
        "BUS INIT: ...ERROR" to { ElmResult.InitError("BUS INIT: ...ERROR") },
        "CAN ERROR" to { ElmResult.CanError("CAN ERROR") },
        "BUS ERROR" to { ElmResult.BusError("BUS ERROR") },
    )

    @Test
    fun `a missing ecu is probed slowly instead of hammered and found again when it answers`() = runTest {
        for ((name, failure) in busFailures) {
            var isEcuThere = true
            val link = TimedLink(
                { testScheduler.currentTime },
                scripted = { request -> if (!isEcuThere && isPoll(request)) failure(request) else null },
            )
            val poller = pollerOn(link)
            poller.start()
            poller.demand("screen", setOf("rpm", "speed", "coolant"))
            elapse(20_000)
            assertEquals(name, PollMode.ACTIVE, poller.stats.value.mode)

            isEcuThere = false
            val ignitionOffAt = testScheduler.currentTime
            elapse(580_000)

            val requests = link.polled(ignitionOffAt).size
            assertTrue("$name: $requests requests in 580 s", requests in 40..70)
            assertEquals(name, PollMode.ENGINE_OFF, poller.stats.value.mode)

            isEcuThere = true
            elapse(15_000)
            assertEquals(name, PollMode.ACTIVE, poller.stats.value.mode)
            val resumedAt = testScheduler.currentTime
            elapse(10_000)
            assertTrue("$name: polling is back at full speed", link.polled(resumedAt).size >= 15)
        }
    }

    @Test
    fun `fewer failures than the limit in a row do not slow the polling down`() = runTest {
        var failuresLeft = 0
        val link = TimedLink(
            { testScheduler.currentTime },
            scripted = { request ->
                if (failuresLeft > 0 && isPoll(request)) {
                    failuresLeft--
                    ElmResult.InitError("BUS INIT: ...ERROR")
                } else {
                    null
                }
            },
        )
        val poller = pollerOn(link)
        poller.start()
        poller.demand("screen", setOf("rpm", "speed"))
        elapse(10_000)

        val limit = PollerConfig().noEcuAfterErrors
        repeat(3) {
            failuresLeft = limit - 1
            val before = link.polled().size
            elapse(10_000)
            assertEquals(PollMode.ACTIVE, poller.stats.value.mode)
            assertTrue("polling went on", link.polled().size - before >= 15)
        }
    }

    @Test
    fun `failures that are not in a row never add up to a missing ecu`() = runTest {
        var counter = 0
        val link = TimedLink(
            { testScheduler.currentTime },
            scripted = { request -> if (isPoll(request) && counter++ % 2 == 0) ElmResult.BusError("BUS ERROR") else null },
        )
        val poller = pollerOn(link)
        poller.start()
        poller.demand("screen", setOf("rpm", "speed"))
        elapse(60_000)

        assertEquals(PollMode.ACTIVE, poller.stats.value.mode)
        assertTrue(link.polled().size > 100)
    }

    // ---------------------------------------------------------------- K-line wake-up

    @Test
    fun `the first request after a pause on a k-line bus gets the wake-up timeout`() = runTest {
        val config = PollerConfig()
        val link = TimedLink({ testScheduler.currentTime })
        val poller = ObdPollScheduler(
            link, backgroundScope, Signals.all, { testScheduler.currentTime }, config,
            monotonicClock = { testScheduler.currentTime },
        )
        poller.start()
        poller.demand("screen", setOf("rpm"))
        elapse(10_000)
        assertTrue(link.polled().all { it.timeoutMs == config.requestTimeoutMs })

        poller.release("screen")
        elapse(20_000)
        val pausedUntilMs = testScheduler.currentTime
        poller.demand("screen", setOf("rpm"))
        elapse(10_000)

        val afterPause = link.polled(pausedUntilMs).map { it.timeoutMs }
        assertEquals(config.wakeUpTimeoutMs, afterPause.first())
        assertTrue(afterPause.drop(1).all { it == config.requestTimeoutMs })
    }

    @Test
    fun `a gap shorter than the bus idle limit keeps the normal timeout`() = runTest {
        val config = PollerConfig()
        val link = TimedLink({ testScheduler.currentTime })
        val poller = ObdPollScheduler(
            link, backgroundScope, Signals.all, { testScheduler.currentTime }, config,
            monotonicClock = { testScheduler.currentTime },
        )
        poller.start()
        poller.demand("screen", setOf("rpm"))
        elapse(5_000)
        poller.release("screen")
        elapse(2_000) // the request in flight finishes, then 1.5 s of silence
        val resumedAt = testScheduler.currentTime
        poller.demand("screen", setOf("rpm"))
        elapse(5_000)

        assertTrue(link.polled(resumedAt).all { it.timeoutMs == config.requestTimeoutMs })
    }

    @Test
    fun `a can bus never gets the wake-up timeout`() = runTest {
        val config = PollerConfig()
        val link = TimedLink({ testScheduler.currentTime }, CAN_INFO, latencyMs = 100)
        val poller = ObdPollScheduler(
            link, backgroundScope, Signals.all, { testScheduler.currentTime }, config,
            monotonicClock = { testScheduler.currentTime },
        )
        poller.start()
        poller.demand("screen", setOf("rpm"))
        elapse(5_000)
        poller.release("screen")
        elapse(20_000)
        poller.demand("screen", setOf("rpm"))
        elapse(5_000)

        assertTrue(link.polled().all { it.timeoutMs == config.requestTimeoutMs })
    }

    // ---------------------------------------------------------------- the connect's 0100

    private val probeReply = ElmResult.Data(listOf("4100BE1FA813"), "4100BE1FA813")

    private fun infoWithProbe(reply: ElmResult.Data?) =
        AdapterInfo("ELM327 v1.5", "3", "ISO 9141-2", 12.4, false, emptySet(), supportedPidsReply = reply)

    @Test
    fun `the probe reply of the connect replaces the first supported pid request`() = runTest {
        val link = TimedLink({ testScheduler.currentTime }, infoWithProbe(probeReply), latencyMs = 100)
        val poller = pollerOn(link)
        poller.start()
        poller.demand("screen", setOf("rpm"))
        elapse(5_000)

        assertTrue("0100" !in link.sent.map { it.base })
        // BE1FA813: bit 31 of the first block is the pointer to 0120, which is still asked for.
        assertEquals("0120", link.sent.first().base)
        assertTrue(0x0C in poller.supportedPids.value.orEmpty())
        assertNotNull(poller.readings.value["rpm"])
    }

    @Test
    fun `without a probe reply the poller still asks for 0100`() = runTest {
        val link = TimedLink({ testScheduler.currentTime }, infoWithProbe(null), latencyMs = 100)
        val poller = pollerOn(link)
        poller.start()
        elapse(2_000)

        assertEquals("0100", link.sent.first().base)
    }

    @Test
    fun `a probe reply without a bitmap is replaced by a fresh 0100`() = runTest {
        val unusable = ElmResult.Data(listOf("7F0112"), "7F0112")
        val link = TimedLink({ testScheduler.currentTime }, infoWithProbe(unusable), latencyMs = 100)
        val poller = pollerOn(link)
        poller.start()
        elapse(10_000)

        assertEquals("0100", link.sent.first().base)
        assertNotNull(poller.supportedPids.value)
    }

    // ---------------------------------------------------------------- response-count digit

    private fun nonDataOnce(failure: ElmResult, times: Int): (SentRequest) -> ElmResult? {
        var left = times
        return { request -> if (isPoll(request) && left > 0) failure.also { left-- } else null }
    }

    @Test
    fun `a data error on the first poll does not cost the connection its response count`() = runTest {
        val link = TimedLink(
            { testScheduler.currentTime }, latencyMs = 100,
            scripted = nonDataOnce(ElmResult.BusError("DATA ERROR"), times = 1),
        )
        val poller = pollerOn(link)
        poller.start()
        poller.demand("screen", setOf("rpm", "speed"))
        elapse(20_000)

        val polled = link.polled().map { it.command }
        assertTrue(polled.size > 20)
        assertTrue(polled.toString(), polled.all { it.length == 5 })
    }

    @Test
    fun `two non-data replies in a row drop the response count and repeat the request`() = runTest {
        val link = TimedLink(
            { testScheduler.currentTime }, latencyMs = 100,
            scripted = nonDataOnce(ElmResult.Garbage("\u0001"), times = 2),
        )
        val poller = pollerOn(link)
        poller.start()
        poller.demand("screen", setOf("rpm", "speed"))
        elapse(20_000)

        val polled = link.polled().map { it.command }
        assertEquals(listOf("010C1", "010D1", "010D"), polled.take(3))
        assertTrue(polled.drop(2).all { it.length == 4 })
        assertNotNull(poller.readings.value["rpm"])
    }
}
