package nl.stijnvdpol.klinedash.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.alert.Alert
import nl.stijnvdpol.klinedash.core.alert.AlertInput
import nl.stijnvdpol.klinedash.core.alert.AlertKind
import nl.stijnvdpol.klinedash.core.alert.AlertRules
import nl.stijnvdpol.klinedash.core.alert.AlertSettings
import nl.stijnvdpol.klinedash.core.alert.AlertSeverity
import nl.stijnvdpol.klinedash.core.alert.AlertState
import nl.stijnvdpol.klinedash.core.alert.AlertEvaluator
import nl.stijnvdpol.klinedash.core.elm.AdapterInfo
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.obd.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlertMonitorTest {
    private val coolantAlert = Alert(AlertKind.COOLANT_HIGH, AlertSeverity.DANGER, 110.0, 0L)

    /** Raises the coolant alert on the first evaluation where coolant is above 100, keeps it afterwards. */
    private class FakeRules(private val alert: Alert) : AlertRules {
        var evaluations = 0
        override fun requiredSignals(settings: AlertSettings) = setOf("coolant")
        override fun evaluate(input: AlertInput, settings: AlertSettings, previous: AlertState): AlertState {
            evaluations++
            val hot = (input.readings["coolant"]?.value ?: 0.0) > 100.0
            return when {
                !hot -> AlertState()
                previous.active.isEmpty() -> AlertState(active = listOf(alert), newlyRaised = listOf(alert))
                else -> AlertState(active = previous.active)
            }
        }
    }

    private val poller = FakePoller()
    private val adapter = AdapterInfo("ELM327 v1.5", "3", "ISO 9141-2", 13.9, usedSavedProtocol = true, unsupportedCommands = emptySet())
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connected(adapter))
    private val published = ArrayList<List<Alert>>()
    private val tones = ArrayList<AlertSeverity>()

    private fun monitor(rules: AlertRules, settings: AlertSettings = AlertSettings()) =
        AlertMonitor(MutableStateFlow(settings), rules, { tones += it }, { published += it }, clock = { 0L })

    @Test
    fun demandsTheRequiredSignalsAndReleasesThemAtTheEnd() = runTest {
        val job = backgroundScope.launch { monitor(FakeRules(coolantAlert)).run(poller, connection) }
        runCurrent()
        assertEquals(setOf("coolant"), poller.demands["alerts"])
        job.cancel()
        runCurrent()
        assertTrue(poller.demands.isEmpty())
        assertEquals(emptyList<Alert>(), published.last())
    }

    @Test
    fun publishesActiveAlertsAndBeepsOncePerActivation() = runTest {
        val rules = FakeRules(coolantAlert)
        backgroundScope.launch { monitor(rules).run(poller, connection) }
        runCurrent()
        assertEquals(emptyList<Alert>(), published.last())

        poller.readings.value = mapOf("coolant" to Reading("coolant", 110.0, 0L))
        advanceTimeBy(3_000) // several evaluations: 1 s tick plus the reading change
        assertEquals(listOf(coolantAlert), published.last())
        assertEquals("one tone for the activation, not one per evaluation", listOf(AlertSeverity.DANGER), tones)
    }

    @Test
    fun noToneWhenSoundIsOffForTheKind() = runTest {
        val silent = AlertSettings(withSound = emptySet())
        backgroundScope.launch { monitor(FakeRules(coolantAlert), silent).run(poller, connection) }
        poller.readings.value = mapOf("coolant" to Reading("coolant", 110.0, 0L))
        advanceTimeBy(2_000)
        assertEquals(listOf(coolantAlert), published.last())
        assertTrue(tones.isEmpty())
    }

    @Test
    fun aDisconnectByTheUserRaisesNoConnectionLostWarning() = runTest {
        backgroundScope.launch { monitor(AlertEvaluator).run(poller, connection) }
        advanceTimeBy(2_000)
        connection.value = ConnectionState.Idle
        advanceTimeBy(2_000)
        assertTrue(published.last().none { it.kind == AlertKind.CONNECTION_LOST })
        assertTrue(tones.isEmpty())
    }

    @Test
    fun aDroppedLinkRaisesConnectionLost() = runTest {
        backgroundScope.launch { monitor(AlertEvaluator).run(poller, connection) }
        advanceTimeBy(2_000)
        connection.value = ConnectionState.Reconnecting(attempt = 1, delayMs = 1_000, reason = "Adapter stopped answering")
        advanceTimeBy(2_000)
        assertTrue(published.last().any { it.kind == AlertKind.CONNECTION_LOST })
    }

    @Test
    fun evaluationsAreThrottledToFourPerSecond() = runTest {
        val rules = FakeRules(coolantAlert)
        backgroundScope.launch { monitor(rules).run(poller, connection) }
        runCurrent()
        repeat(100) {
            poller.readings.value = mapOf("coolant" to Reading("coolant", 80.0 + it % 5, 0L))
            advanceTimeBy(10)
        }
        // 1 s of changes every 10 ms: at most 4 per second plus the first one.
        assertTrue("evaluations: ${rules.evaluations}", rules.evaluations <= 6)
    }

    /** Remembers the input of every evaluation. */
    private class RecordingRules : AlertRules {
        val inputs = ArrayList<AlertInput>()
        override fun requiredSignals(settings: AlertSettings) = setOf("coolant")
        override fun evaluate(input: AlertInput, settings: AlertSettings, previous: AlertState): AlertState {
            inputs += input
            return AlertState()
        }
    }

    @Test
    fun theRulesMeasureTimeOnTheMonotonicClockWhateverTheWallClockDoes() = runTest {
        var wallStepMs = 0L
        val wallNowMs = { 1_792_677_787_000L + wallStepMs + currentTime }
        val rules = RecordingRules()
        val monitor = AlertMonitor(
            MutableStateFlow(AlertSettings()), rules, { }, { }, clock = wallNowMs, elapsed = { currentTime },
        )
        backgroundScope.launch { monitor.run(poller, connection) }
        poller.readings.value = mapOf("coolant" to Reading("coolant", 90.0, wallNowMs() - 400))
        advanceTimeBy(1_100)
        val beforeStep = rules.inputs.last()
        assertTrue("the rules' now is the monotonic time: ${beforeStep.nowMs}", currentTime - beforeStep.nowMs in 0L..1_000L)

        wallStepMs = -7 * 3_600_000L // the unit's clock is set back by hours
        poller.readings.value = mapOf("coolant" to Reading("coolant", 91.0, wallNowMs() - 400))
        advanceTimeBy(1_100)
        val after = rules.inputs.last()
        assertTrue("time did not jump with the wall clock: ${after.nowMs}", after.nowMs - beforeStep.nowMs in 1L..3_000L)
        val ageMs = after.nowMs - checkNotNull(after.readings["coolant"]).timestampMs
        assertTrue("a reading taken 400 ms before the step is not hours old: $ageMs", ageMs in 400L..2_000L)
    }

    @Test
    fun readingsMovedOntoTheMonotonicClockKeepTheirAge() {
        val readings = mapOf("rpm" to Reading("rpm", 900.0, 99_000L), "speed" to Reading("speed", 10.0, 100_000L))
        val moved = readings.onMonotonicClock(wallNowMs = 100_000L, elapsedNowMs = 5_000L)
        assertEquals(4_000L, moved.getValue("rpm").timestampMs)
        assertEquals(5_000L, moved.getValue("speed").timestampMs)
        assertEquals(900.0, moved.getValue("rpm").value, 0.0)
    }
}
