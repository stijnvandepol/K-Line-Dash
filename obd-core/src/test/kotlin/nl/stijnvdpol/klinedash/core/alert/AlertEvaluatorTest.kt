package nl.stijnvdpol.klinedash.core.alert

import nl.stijnvdpol.klinedash.core.obd.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEvaluatorTest {

    private val settings = AlertSettings()
    private val t0 = 1_000_000L

    /** Evaluates one moment; readings are stamped [ageMs] before [nowMs] unless given a pair. */
    private fun step(
        previous: AlertState,
        nowMs: Long,
        vararg values: Pair<String, Double>,
        connected: Boolean = true,
        using: AlertSettings = settings,
        ageMs: Long = 0,
    ): AlertState {
        val readings = values.associate { (id, value) -> id to Reading(id, value, nowMs - ageMs) }
        return AlertEvaluator.evaluate(AlertInput(readings, connected, nowMs), using, previous)
    }

    private fun AlertState.alert(kind: AlertKind): Alert? = active.firstOrNull { it.kind == kind }

    private fun AlertState.isRaised(kind: AlertKind): Boolean = newlyRaised.any { it.kind == kind }

    // ------------------------------------------------------------ required signals

    @Test
    fun requiredSignalsFollowTheEnabledKinds() {
        val all = AlertEvaluator.requiredSignals(settings)
        assertEquals(setOf("coolant", "battery", "rpm", "dtc_count", "speed", "mil_on"), all)

        val coolantOnly = settings.copy(enabled = setOf(AlertKind.COOLANT_HIGH))
        assertEquals(setOf("coolant"), AlertEvaluator.requiredSignals(coolantOnly))

        val voltageHigh = settings.copy(enabled = setOf(AlertKind.VOLTAGE_HIGH))
        assertEquals(setOf("battery", "rpm"), AlertEvaluator.requiredSignals(voltageHigh))

        val connectionOnly = settings.copy(enabled = setOf(AlertKind.CONNECTION_LOST))
        assertTrue(AlertEvaluator.requiredSignals(connectionOnly).isEmpty())
        assertTrue(AlertEvaluator.requiredSignals(settings.copy(enabled = emptySet())).isEmpty())
    }

    // ------------------------------------------------------------ coolant

    @Test
    fun coolantBelowCautionRaisesNothing() {
        val state = step(AlertState(), t0, "coolant" to 102.0)
        assertTrue(state.active.isEmpty())
        assertTrue(state.newlyRaised.isEmpty())
    }

    @Test
    fun coolantAtCautionRaisesCautionWithValueAndStartTime() {
        val state = step(AlertState(), t0, "coolant" to 103.0)
        val alert = state.alert(AlertKind.COOLANT_HIGH)!!
        assertEquals(AlertSeverity.CAUTION, alert.severity)
        assertEquals(103.0, alert.value!!, 0.0)
        assertEquals(t0, alert.sinceMs)
        assertTrue(state.isRaised(AlertKind.COOLANT_HIGH))
    }

    @Test
    fun coolantEscalationToDangerCountsAsNewlyRaisedAndKeepsStartTime() {
        val caution = step(AlertState(), t0, "coolant" to 104.0)
        val danger = step(caution, t0 + 2_000, "coolant" to 108.0)
        assertEquals(AlertSeverity.DANGER, danger.alert(AlertKind.COOLANT_HIGH)!!.severity)
        assertEquals(t0, danger.alert(AlertKind.COOLANT_HIGH)!!.sinceMs)
        assertTrue(danger.isRaised(AlertKind.COOLANT_HIGH))

        val unchanged = step(danger, t0 + 4_000, "coolant" to 109.0)
        assertFalse(unchanged.isRaised(AlertKind.COOLANT_HIGH))
    }

    @Test
    fun coolantClearsOnlyBelowCautionMinusHysteresis() {
        val raised = step(AlertState(), t0, "coolant" to 104.0)
        val inBand = step(raised, t0 + 2_000, "coolant" to 101.5)
        assertEquals(AlertSeverity.CAUTION, inBand.alert(AlertKind.COOLANT_HIGH)!!.severity)
        assertFalse(inBand.isRaised(AlertKind.COOLANT_HIGH))

        val cleared = step(inBand, t0 + 4_000, "coolant" to 100.9)
        assertNull(cleared.alert(AlertKind.COOLANT_HIGH))
    }

    @Test
    fun coolantDangerFallsBackToCautionOnlyBelowDangerMinusHysteresis() {
        val danger = step(AlertState(), t0, "coolant" to 109.0)
        val inBand = step(danger, t0 + 2_000, "coolant" to 106.5)
        assertEquals(AlertSeverity.DANGER, inBand.alert(AlertKind.COOLANT_HIGH)!!.severity)

        val caution = step(inBand, t0 + 4_000, "coolant" to 105.9)
        assertEquals(AlertSeverity.CAUTION, caution.alert(AlertKind.COOLANT_HIGH)!!.severity)
        assertFalse(caution.isRaised(AlertKind.COOLANT_HIGH))
    }

    @Test
    fun coolantRespectsTheUsersLimits() {
        val custom = settings.copy(coolantCautionC = 95.0, coolantDangerC = 100.0)
        val state = step(AlertState(), t0, "coolant" to 96.0, using = custom)
        assertEquals(AlertSeverity.CAUTION, state.alert(AlertKind.COOLANT_HIGH)!!.severity)
    }

    // ------------------------------------------------------------ freshness

    @Test
    fun staleCoolantReadingIsIgnoredAndClearsTheAlert() {
        val raised = step(AlertState(), t0, "coolant" to 110.0)
        val stale = step(raised, t0 + 60_000, "coolant" to 110.0, ageMs = 16_000)
        assertTrue(stale.active.isEmpty())

        val stillFresh = step(raised, t0 + 60_000, "coolant" to 110.0, ageMs = 15_000)
        assertEquals(AlertSeverity.DANGER, stillFresh.alert(AlertKind.COOLANT_HIGH)!!.severity)
    }

    @Test
    fun freshnessWindowDependsOnThePollTier() {
        assertEquals(10_000L, AlertEvaluator.freshnessWindowMs("rpm"))
        assertEquals(15_000L, AlertEvaluator.freshnessWindowMs("coolant"))
        assertEquals(30_000L, AlertEvaluator.freshnessWindowMs("battery"))
        assertEquals(30_000L, AlertEvaluator.freshnessWindowMs("mil_on"))
        assertEquals(10_000L, AlertEvaluator.freshnessWindowMs("unknown_signal"))
    }

    @Test
    fun missingReadingsRaiseNothing() {
        val state = step(AlertState(), t0)
        assertTrue(state.active.isEmpty())
    }

    // ------------------------------------------------------------ voltage low

    @Test
    fun lowRunningVoltageIsRaisedOnlyAfterTheDebounce() {
        val running = arrayOf("battery" to 13.0, "rpm" to 2_000.0)
        val first = step(AlertState(), t0, *running)
        assertTrue(first.active.isEmpty())

        val tooSoon = step(first, t0 + 9_999, *running)
        assertTrue(tooSoon.active.isEmpty())

        val raised = step(tooSoon, t0 + 10_000, *running)
        val alert = raised.alert(AlertKind.VOLTAGE_LOW)!!
        assertEquals(AlertSeverity.CAUTION, alert.severity)
        assertEquals(t0, alert.sinceMs)
        assertEquals(13.0, alert.value!!, 0.0)
        assertTrue(raised.isRaised(AlertKind.VOLTAGE_LOW))
    }

    @Test
    fun voltageDipThatRecoversWithinTheDebounceNeverRaises() {
        val dip = step(AlertState(), t0, "battery" to 10.5, "rpm" to 300.0)
        val recovered = step(dip, t0 + 5_000, "battery" to 12.6, "rpm" to 300.0)
        assertTrue(recovered.active.isEmpty())

        val dipAgain = step(recovered, t0 + 12_000, "battery" to 10.5, "rpm" to 300.0)
        val stillWaiting = step(dipAgain, t0 + 20_000, "battery" to 10.5, "rpm" to 300.0)
        assertTrue("timer restarted after the recovery", stillWaiting.active.isEmpty())
    }

    @Test
    fun lowVoltageDangerLevelsDifferForRunningAndEngineOff() {
        val running = raiseVoltage("battery" to 12.4, "rpm" to 2_000.0)
        assertEquals(AlertSeverity.DANGER, running.alert(AlertKind.VOLTAGE_LOW)!!.severity)

        val runningCaution = raiseVoltage("battery" to 12.6, "rpm" to 2_000.0)
        assertEquals(AlertSeverity.CAUTION, runningCaution.alert(AlertKind.VOLTAGE_LOW)!!.severity)

        val off = raiseVoltage("battery" to 11.4, "rpm" to 0.0)
        assertEquals(AlertSeverity.DANGER, off.alert(AlertKind.VOLTAGE_LOW)!!.severity)

        val offCaution = raiseVoltage("battery" to 11.8, "rpm" to 0.0)
        assertEquals(AlertSeverity.CAUTION, offCaution.alert(AlertKind.VOLTAGE_LOW)!!.severity)
    }

    @Test
    fun engineOffUsesTheLowerLimit() {
        val state = raiseVoltage("battery" to 12.5, "rpm" to 0.0)
        assertTrue("12.5 V is fine with the engine off", state.active.isEmpty())
        val running = raiseVoltage("battery" to 12.5, "rpm" to 800.0)
        assertTrue("12.5 V is low with the engine running", running.alert(AlertKind.VOLTAGE_LOW) != null)
    }

    @Test
    fun lowVoltageClearsOnlyAboveLimitPlusHysteresis() {
        val raised = raiseVoltage("battery" to 13.0, "rpm" to 2_000.0)
        val inBand = step(raised, t0 + 20_000, "battery" to 13.3, "rpm" to 2_000.0)
        assertTrue(inBand.alert(AlertKind.VOLTAGE_LOW) != null)
        assertFalse(inBand.isRaised(AlertKind.VOLTAGE_LOW))

        val cleared = step(inBand, t0 + 22_000, "battery" to 13.4, "rpm" to 2_000.0)
        assertNull(cleared.alert(AlertKind.VOLTAGE_LOW))
    }

    @Test
    fun lowVoltageEscalatesToDangerWithoutDebounce() {
        val caution = raiseVoltage("battery" to 12.8, "rpm" to 2_000.0)
        val danger = step(caution, t0 + 20_000, "battery" to 12.0, "rpm" to 2_000.0)
        assertEquals(AlertSeverity.DANGER, danger.alert(AlertKind.VOLTAGE_LOW)!!.severity)
        assertTrue(danger.isRaised(AlertKind.VOLTAGE_LOW))
    }

    @Test
    fun voltageNeedsAFreshRpmToKnowTheEngineState() {
        val noRpm = step(AlertState(), t0, "battery" to 9.0)
        val later = step(noRpm, t0 + 20_000, "battery" to 9.0)
        assertTrue(later.active.isEmpty())
    }

    // ------------------------------------------------------------ voltage high

    @Test
    fun highRunningVoltageIsDebouncedAndGradedCautionThenDanger() {
        val high = raiseVoltage("battery" to 15.2, "rpm" to 2_000.0)
        val alert = high.alert(AlertKind.VOLTAGE_HIGH)!!
        assertEquals(AlertSeverity.CAUTION, alert.severity)
        assertNull(high.alert(AlertKind.VOLTAGE_LOW))

        val danger = raiseVoltage("battery" to 15.6, "rpm" to 2_000.0)
        assertEquals(AlertSeverity.DANGER, danger.alert(AlertKind.VOLTAGE_HIGH)!!.severity)
    }

    @Test
    fun highVoltageClearsBelowLimitMinusHysteresis() {
        val raised = raiseVoltage("battery" to 15.2, "rpm" to 2_000.0)
        val inBand = step(raised, t0 + 20_000, "battery" to 14.9, "rpm" to 2_000.0)
        assertTrue(inBand.alert(AlertKind.VOLTAGE_HIGH) != null)

        val cleared = step(inBand, t0 + 22_000, "battery" to 14.7, "rpm" to 2_000.0)
        assertNull(cleared.alert(AlertKind.VOLTAGE_HIGH))
    }

    @Test
    fun highVoltageWithTheEngineOffIsNotAnAlert() {
        val state = raiseVoltage("battery" to 15.5, "rpm" to 0.0)
        assertNull(state.alert(AlertKind.VOLTAGE_HIGH))
    }

    @Test
    fun stoppingTheEngineClearsARunningVoltageAlert() {
        val raised = raiseVoltage("battery" to 13.0, "rpm" to 2_000.0)
        val stopped = step(raised, t0 + 20_000, "battery" to 12.4, "rpm" to 0.0)
        assertNull(stopped.alert(AlertKind.VOLTAGE_LOW))
    }

    /** Feeds the same voltage readings at t0 and t0 + 10 s, which is exactly the debounce. */
    private fun raiseVoltage(vararg readings: Pair<String, Double>): AlertState {
        val first = step(AlertState(), t0, *readings)
        return step(first, t0 + 10_000, *readings)
    }

    // ------------------------------------------------------------ MIL

    @Test
    fun milComingOnDuringTheSessionIsRaisedOnceAndStaysWhileOn() {
        val off = step(AlertState(), t0, "mil_on" to 0.0)
        assertTrue(off.active.isEmpty())

        val on = step(off, t0 + 15_000, "mil_on" to 1.0)
        assertEquals(AlertSeverity.CAUTION, on.alert(AlertKind.MIL_ON)!!.severity)
        assertNull(on.alert(AlertKind.MIL_ON)!!.value)
        assertTrue(on.isRaised(AlertKind.MIL_ON))

        val stillOn = step(on, t0 + 30_000, "mil_on" to 1.0)
        assertTrue(stillOn.alert(AlertKind.MIL_ON) != null)
        assertFalse(stillOn.isRaised(AlertKind.MIL_ON))
        assertEquals(t0 + 15_000, stillOn.alert(AlertKind.MIL_ON)!!.sinceMs)

        val cleared = step(stillOn, t0 + 45_000, "mil_on" to 0.0)
        assertNull(cleared.alert(AlertKind.MIL_ON))
    }

    @Test
    fun milAlreadyOnAtTheFirstReadingIsRaisedOnceAsCaution() {
        val first = step(AlertState(), t0, "mil_on" to 1.0)
        assertEquals(AlertSeverity.CAUTION, first.alert(AlertKind.MIL_ON)!!.severity)
        assertTrue(first.isRaised(AlertKind.MIL_ON))

        val second = step(first, t0 + 15_000, "mil_on" to 1.0)
        assertFalse(second.isRaised(AlertKind.MIL_ON))
    }

    @Test
    fun staleMilReadingHoldsTheActiveAlert() {
        val on = step(AlertState(), t0, "mil_on" to 1.0)
        val stale = step(on, t0 + 120_000, "mil_on" to 1.0, ageMs = 60_000)
        assertTrue(stale.alert(AlertKind.MIL_ON) != null)
        assertFalse(stale.isRaised(AlertKind.MIL_ON))
    }

    // ------------------------------------------------------------ new DTC

    @Test
    fun codesAlreadyStoredAtTheFirstReadingAreOnlyTheBaseline() {
        val first = step(AlertState(), t0, "dtc_count" to 2.0, "speed" to 50.0)
        assertTrue(first.active.isEmpty())
        val same = step(first, t0 + 15_000, "dtc_count" to 2.0, "speed" to 50.0)
        assertTrue(same.active.isEmpty())
    }

    @Test
    fun countRisingWhileDrivingRaisesAndStaysUntilTheCountDrops() {
        val baseline = step(AlertState(), t0, "dtc_count" to 0.0, "speed" to 60.0)
        val raised = step(baseline, t0 + 15_000, "dtc_count" to 1.0, "speed" to 60.0)
        val alert = raised.alert(AlertKind.NEW_DTC)!!
        assertEquals(AlertSeverity.CAUTION, alert.severity)
        assertEquals(t0 + 15_000, alert.sinceMs)
        assertTrue(raised.isRaised(AlertKind.NEW_DTC))

        val stopped = step(raised, t0 + 30_000, "dtc_count" to 1.0, "speed" to 0.0)
        assertTrue("stays active when the car stops", stopped.alert(AlertKind.NEW_DTC) != null)
        assertFalse(stopped.isRaised(AlertKind.NEW_DTC))

        val cleared = step(stopped, t0 + 45_000, "dtc_count" to 0.0, "speed" to 0.0)
        assertNull(cleared.alert(AlertKind.NEW_DTC))
    }

    @Test
    fun countRisingWhileParkedWaitsForTheCarToMove() {
        val baseline = step(AlertState(), t0, "dtc_count" to 0.0, "speed" to 0.0)
        val parked = step(baseline, t0 + 15_000, "dtc_count" to 1.0, "speed" to 0.0)
        assertTrue(parked.active.isEmpty())

        val noSpeed = step(parked, t0 + 30_000, "dtc_count" to 1.0)
        assertTrue(noSpeed.active.isEmpty())

        val moving = step(noSpeed, t0 + 45_000, "dtc_count" to 1.0, "speed" to 5.0)
        assertTrue(moving.isRaised(AlertKind.NEW_DTC))
    }

    @Test
    fun baselineResetsAfterTheCodesAreCleared() {
        val baseline = step(AlertState(), t0, "dtc_count" to 3.0, "speed" to 60.0)
        val cleared = step(baseline, t0 + 15_000, "dtc_count" to 0.0, "speed" to 60.0)
        assertTrue(cleared.active.isEmpty())

        val againTwo = step(cleared, t0 + 30_000, "dtc_count" to 2.0, "speed" to 60.0)
        assertTrue("2 is new relative to the reset baseline 0", againTwo.isRaised(AlertKind.NEW_DTC))
    }

    @Test
    fun staleCodeCountHoldsAnActiveAlert() {
        val baseline = step(AlertState(), t0, "dtc_count" to 0.0, "speed" to 60.0)
        val raised = step(baseline, t0 + 15_000, "dtc_count" to 1.0, "speed" to 60.0)
        val stale = step(raised, t0 + 100_000, "dtc_count" to 1.0, ageMs = 60_000)
        assertTrue(stale.alert(AlertKind.NEW_DTC) != null)
    }

    @Test
    fun losingTheConnectionStartsANewDtcBaseline() {
        val baseline = step(AlertState(), t0, "dtc_count" to 0.0, "speed" to 60.0)
        val dropped = step(baseline, t0 + 5_000, connected = false)
        val reconnected = step(dropped, t0 + 20_000, "dtc_count" to 1.0, "speed" to 60.0)
        assertNull("1 is the new baseline of the second connection", reconnected.alert(AlertKind.NEW_DTC))
    }

    // ------------------------------------------------------------ connection

    @Test
    fun connectionLostIsNeverRaisedBeforeTheFirstConnection() {
        val state = step(AlertState(), t0, connected = false)
        assertTrue(state.active.isEmpty())
        val later = step(state, t0 + 60_000, connected = false)
        assertTrue(later.active.isEmpty())
    }

    @Test
    fun connectionLostIsRaisedAsDangerAfterADropAndClearsOnReconnect() {
        val connected = step(AlertState(), t0, connected = true)
        assertTrue(connected.active.isEmpty())

        val lost = step(connected, t0 + 1_000, connected = false)
        val alert = lost.alert(AlertKind.CONNECTION_LOST)!!
        assertEquals(AlertSeverity.DANGER, alert.severity)
        assertEquals(t0 + 1_000, alert.sinceMs)
        assertTrue(lost.isRaised(AlertKind.CONNECTION_LOST))

        val stillLost = step(lost, t0 + 9_000, connected = false)
        assertFalse(stillLost.isRaised(AlertKind.CONNECTION_LOST))
        assertEquals(t0 + 1_000, stillLost.alert(AlertKind.CONNECTION_LOST)!!.sinceMs)

        val back = step(stillLost, t0 + 20_000, connected = true)
        assertNull(back.alert(AlertKind.CONNECTION_LOST))

        val lostAgain = step(back, t0 + 30_000, connected = false)
        assertTrue(lostAgain.isRaised(AlertKind.CONNECTION_LOST))
    }

    // ------------------------------------------------------------ disabled kinds and combinations

    @Test
    fun disabledKindsAreNeverRaised() {
        val onlyCoolant = settings.copy(enabled = setOf(AlertKind.COOLANT_HIGH))
        val connected = step(AlertState(), t0, "coolant" to 90.0, using = onlyCoolant)
        val worst = step(
            connected, t0 + 20_000,
            "coolant" to 90.0, "battery" to 9.0, "rpm" to 2_000.0, "mil_on" to 1.0,
            "dtc_count" to 5.0, "speed" to 60.0,
            connected = false, using = onlyCoolant,
        )
        assertTrue(worst.active.isEmpty())
    }

    @Test
    fun disablingAKindDropsItsActiveAlert() {
        val raised = step(AlertState(), t0, "coolant" to 110.0)
        val disabled = step(
            raised, t0 + 1_000, "coolant" to 110.0,
            using = settings.copy(enabled = setOf(AlertKind.MIL_ON)),
        )
        assertTrue(disabled.active.isEmpty())
    }

    @Test
    fun severalAlertsCanBeActiveAtOnce() {
        val state = step(AlertState(), t0, "coolant" to 109.0, "mil_on" to 1.0)
        assertEquals(setOf(AlertKind.COOLANT_HIGH, AlertKind.MIL_ON), state.active.map { it.kind }.toSet())
        assertEquals(2, state.newlyRaised.size)
    }
}
