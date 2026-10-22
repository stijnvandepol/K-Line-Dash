package nl.stijnvdpol.klinedash.service

import android.database.SQLException
import java.io.File
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.obd.ObdPoller
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.data.TripDao
import nl.stijnvdpol.klinedash.data.TripSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TripRecorderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val baseMs = 1_792_677_787_000L
    private val dao = FakeTripDao()
    private val poller = FakePoller()
    private val pollerFlow = MutableStateFlow<ObdPoller?>(poller)
    private val settings = MutableStateFlow(TripSettings(autoRecord = true))

    private fun TestScope.newRecorder(tripDao: TripDao = dao) = TripRecorder(
        tripsDir = File(folder.root, "trips"),
        tripDao = tripDao,
        settings = settings,
        poller = pollerFlow,
        clock = { baseMs + currentTime },
        zone = ZoneOffset.UTC,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun TestScope.engineRunning() {
        val now = baseMs + currentTime
        poller.readings.value = mapOf(
            "rpm" to Reading("rpm", 900.0, now),
            "speed" to Reading("speed", 36.0, now),
            "coolant" to Reading("coolant", 88.0, now),
        )
    }

    @Test
    fun engineStartsARecordingAndTheGraceEndsItWithASummary() = runTest {
        val recorder = newRecorder()
        backgroundScope.launch { recorder.run() }
        runCurrent()
        assertEquals("rpm is demanded to notice the engine", setOf("rpm"), poller.demands["trip"])

        engineRunning()
        advanceTimeBy(2_500)
        val recording = recorder.state.value as RecordingState.Recording
        assertTrue(recording.isAuto)
        assertEquals("trip-20261022-140307.csv".length, recording.fileName.length)
        assertEquals("recording demands what the summary needs", setOf("rpm", "speed", "coolant"), poller.demands["trip"])

        advanceTimeBy(30_000) // the rpm reading goes stale: the engine counts as off
        assertTrue(recorder.state.value is RecordingState.Recording)
        advanceTimeBy(AUTO_STOP_GRACE_MS + 15_000)
        assertEquals(RecordingState.Idle, recorder.state.value)

        val trip = dao.rows.value.single()
        assertTrue(trip.endMs != null && trip.sampleCount > 30)
        assertEquals(900.0, checkNotNull(trip.maxRpm), 0.001)
        assertTrue(File(folder.root, "trips/${trip.fileName}").length() > 0)
        assertEquals("back to only the engine detector", setOf("rpm"), poller.demands["trip"])
    }

    @Test
    fun noAutomaticRecordingWhenTheOptionIsOff() = runTest {
        settings.value = TripSettings(autoRecord = false)
        val recorder = newRecorder()
        backgroundScope.launch { recorder.run() }
        engineRunning()
        advanceTimeBy(5_000)
        assertEquals(RecordingState.Idle, recorder.state.value)
        assertNull("nothing is demanded without auto-record or a recording", poller.demands["trip"])
    }

    @Test
    fun manualStartNeedsALinkAndStopDoesNotRestartAutomatically() = runTest {
        pollerFlow.value = null
        val recorder = newRecorder()
        assertEquals(StartResult.NotConnected, recorder.start())

        pollerFlow.value = poller
        backgroundScope.launch { recorder.run() }
        assertEquals(StartResult.Started, recorder.start())
        assertEquals(StartResult.AlreadyRecording, recorder.start())
        engineRunning()
        advanceTimeBy(3_000)
        assertTrue(!(recorder.state.value as RecordingState.Recording).isAuto)

        recorder.stop()
        engineRunning()
        advanceTimeBy(3_000)
        assertEquals("the engine is still running but the user stopped the trip", RecordingState.Idle, recorder.state.value)
        assertEquals(1, dao.rows.value.size)
        assertTrue(dao.rows.value.single().endMs != null)
    }

    @Test
    fun stoppingBeforeAnySampleLeavesNoTrip() = runTest {
        val recorder = newRecorder()
        assertEquals(StartResult.Started, recorder.start())
        recorder.stop()
        assertTrue(dao.rows.value.isEmpty())
        assertEquals(0, File(folder.root, "trips").listFiles()?.size)
    }

    @Test
    fun ignitionOffFinishesTheTripAtOnceAndTheNextDriveRecordsAgain() = runTest {
        val recorder = newRecorder()
        backgroundScope.launch { recorder.run() }
        engineRunning()
        advanceTimeBy(5_000)
        assertTrue(recorder.state.value is RecordingState.Recording)

        recorder.finishForIgnitionOff()
        assertEquals("finished without waiting for the grace period", RecordingState.Idle, recorder.state.value)
        assertTrue(dao.rows.value.single().endMs != null)
        advanceTimeBy(3_000)
        assertEquals("the last fresh rpm of this drive starts no new trip", RecordingState.Idle, recorder.state.value)

        pollerFlow.value = null // ACC off closed the link: the engine counts as off
        advanceTimeBy(2_000)
        pollerFlow.value = poller
        engineRunning()
        advanceTimeBy(3_000)
        assertTrue("the next drive starts its own trip", recorder.state.value is RecordingState.Recording)
        assertEquals(2, dao.rows.value.size)
    }

    @Test
    fun ignitionOffAlsoEndsAManualRecording() = runTest {
        val recorder = newRecorder()
        backgroundScope.launch { recorder.run() }
        assertEquals(StartResult.Started, recorder.start())
        engineRunning()
        advanceTimeBy(3_000)
        recorder.finishForIgnitionOff()
        assertEquals(RecordingState.Idle, recorder.state.value)
        assertTrue(dao.rows.value.single().endMs != null)
    }

    @Test
    fun cancellingTheLoopFinishesTheRunningTrip() = runTest {
        val recorder = newRecorder()
        val loop = backgroundScope.launch { recorder.run() }
        engineRunning()
        advanceTimeBy(5_000)
        assertTrue(recorder.state.value is RecordingState.Recording)
        loop.cancel()
        runCurrent()
        assertEquals(RecordingState.Idle, recorder.state.value)
        assertTrue(dao.rows.value.single().endMs != null)
        assertNull("the demand is released with the loop", poller.demands["trip"])
    }

    /** The trip table of [rows] on storage that turns full: writing the end of a trip then fails. */
    private class FullDiskTripDao(private val rows: FakeTripDao) : TripDao by rows {
        var isFull = false

        override suspend fun finish(
            id: Long,
            endMs: Long,
            sampleCount: Int,
            distanceKm: Double,
            maxRpm: Double?,
            maxSpeedKmh: Double?,
            maxCoolantC: Double?,
        ) {
            if (isFull) throw SQLException("database or disk is full")
            rows.finish(id, endMs, sampleCount, distanceKm, maxRpm, maxSpeedKmh, maxCoolantC)
        }
    }

    @Test
    fun aTripWhoseEndCannotBeSavedIsLeftForRecoveryInsteadOfCrashing() = runTest {
        val fullDisk = FullDiskTripDao(dao)
        val recorder = newRecorder(fullDisk)
        val loop = backgroundScope.launch { recorder.run() }
        assertEquals(StartResult.Started, recorder.start())
        engineRunning()
        advanceTimeBy(3_000)
        fullDisk.isFull = true

        recorder.stop()

        assertEquals(RecordingState.Idle, recorder.state.value)
        assertNotNull("the user is told why", recorder.lastError.value)
        assertNull("the row stays unfinished, so the next app start recovers it", dao.rows.value.single().endMs)
        advanceTimeBy(3_000)
        assertTrue("the sampling loop keeps running", loop.isActive)
    }
}
