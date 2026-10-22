package nl.stijnvdpol.klinedash.service

import nl.stijnvdpol.klinedash.core.obd.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRecordPolicyTest {
    private val grace = 120_000L
    private val policy = AutoRecordPolicy(grace)
    private var now = 1_000_000L

    private fun decide(engine: Boolean, recording: RecordingOrigin?, auto: Boolean = true) =
        policy.decide(now, engine, auto, recording)

    @Test
    fun engineRunningStartsARecording() {
        assertEquals(AutoRecordAction.START, decide(engine = true, recording = null))
    }

    @Test
    fun noStartWhenAutoRecordIsOffOrEngineIsOff() {
        assertEquals(AutoRecordAction.NONE, decide(engine = true, recording = null, auto = false))
        assertEquals(AutoRecordAction.NONE, decide(engine = false, recording = null))
    }

    @Test
    fun automaticRecordingStopsOnlyAfterTheFullGracePeriod() {
        assertEquals(AutoRecordAction.NONE, decide(engine = true, recording = RecordingOrigin.AUTO))
        now += 5_000
        assertEquals(AutoRecordAction.NONE, decide(engine = false, recording = RecordingOrigin.AUTO))
        now += grace - 1
        assertEquals(AutoRecordAction.NONE, decide(engine = false, recording = RecordingOrigin.AUTO))
        now += 1
        assertEquals(AutoRecordAction.STOP, decide(engine = false, recording = RecordingOrigin.AUTO))
    }

    @Test
    fun engineComingBackInsideTheGraceRestartsTheCountdown() {
        decide(engine = false, recording = RecordingOrigin.AUTO)
        now += grace - 1_000
        assertEquals(AutoRecordAction.NONE, decide(engine = true, recording = RecordingOrigin.AUTO))
        now += 1_000
        assertEquals(AutoRecordAction.NONE, decide(engine = false, recording = RecordingOrigin.AUTO))
        now += grace - 1
        assertEquals(AutoRecordAction.NONE, decide(engine = false, recording = RecordingOrigin.AUTO))
        now += 1
        assertEquals(AutoRecordAction.STOP, decide(engine = false, recording = RecordingOrigin.AUTO))
    }

    @Test
    fun manualRecordingIsNeverStoppedByTheEngine() {
        decide(engine = false, recording = RecordingOrigin.MANUAL)
        now += grace * 10
        assertEquals(AutoRecordAction.NONE, decide(engine = false, recording = RecordingOrigin.MANUAL))
    }

    @Test
    fun afterAStopTheEngineMustHaveBeenOffBeforeTheNextAutomaticStart() {
        policy.suppressStartUntilEngineStops()
        assertEquals(AutoRecordAction.NONE, decide(engine = true, recording = null))
        now += 1_000
        assertEquals(AutoRecordAction.NONE, decide(engine = true, recording = null))
        now += 1_000
        assertEquals(AutoRecordAction.NONE, decide(engine = false, recording = null))
        now += 1_000
        assertEquals(AutoRecordAction.START, decide(engine = true, recording = null))
    }

    @Test
    fun engineDetectionNeedsAFreshRpmAboveCranking() {
        val t = 50_000L
        fun rpm(value: Double, at: Long) = mapOf("rpm" to Reading("rpm", value, at))
        assertTrue(isEngineRunning(rpm(850.0, t), t + ENGINE_READING_FRESH_MS))
        assertFalse(isEngineRunning(rpm(850.0, t), t + ENGINE_READING_FRESH_MS + 1))
        assertFalse(isEngineRunning(rpm(250.0, t), t))
        assertFalse(isEngineRunning(emptyMap(), t))
    }
}
