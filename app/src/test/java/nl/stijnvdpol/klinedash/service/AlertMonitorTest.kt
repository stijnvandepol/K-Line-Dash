package nl.stijnvdpol.klinedash.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.alert.Alert
import nl.stijnvdpol.klinedash.core.alert.AlertInput
import nl.stijnvdpol.klinedash.core.alert.AlertKind
import nl.stijnvdpol.klinedash.core.alert.AlertRules
import nl.stijnvdpol.klinedash.core.alert.AlertSettings
import nl.stijnvdpol.klinedash.core.alert.AlertSeverity
import nl.stijnvdpol.klinedash.core.alert.AlertState
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
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
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
}
