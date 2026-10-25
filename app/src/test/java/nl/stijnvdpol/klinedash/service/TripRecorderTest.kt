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
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.data.TripDao
import nl.stijnvdpol.klinedash.data.TripSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val HOUR_MS = 3_600_000L

@OptIn(ExperimentalCoroutinesApi::class)
class TripRecorderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val baseMs = 1_792_677_787_000L
    private val dao = FakeTripDao()
    private val poller = FakePoller()
    private val pollerFlow = MutableStateFlow<ObdPoller?>(poller)
    private val settings = MutableStateFlow(TripSettings(autoRecord = true))

    /** How far the unit's wall clock has been stepped (no clock chip: GPS or NTP time arrives late); the monotonic clock is unaffected. */
    private var wallStepMs = 0L

    private fun TestScope.newRecorder(tripDao: TripDao = dao) = TripRecorder(
        tripsDir = File(folder.root, "trips"),
        tripDao = tripDao,
        settings = settings,
        poller = pollerFlow,
        clock = { baseMs + currentTime + wallStepMs },
        elapsed = { currentTime },
        zone = ZoneOffset.UTC,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun TestScope.engineRunning() {
        val now = baseMs + currentTime + wallStepMs
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
        assertEquals(
            "recording demands what the summary, the fuel estimate and the full-throttle time need",
            setOf("rpm", "speed", "coolant", "maf", "throttle"),
            poller.demands["trip"],
        )

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

    @Test
    fun aWallClockSteppedBackByHoursStillWritesTheHeaderAndTheTripIsNotLost() = runTest {
        val recorder = newRecorder()
        backgroundScope.launch { recorder.run() }
        engineRunning()
        advanceTimeBy(3_000)
        val fileName = (recorder.state.value as RecordingState.Recording).fileName
        wallStepMs = -3 * HOUR_MS
        repeat(25) {
            engineRunning()
            advanceTimeBy(1_000)
        }
        val text = File(folder.root, "trips/$fileName").readText(Charsets.UTF_8)
        assertTrue("the header and rows are on disk after the settle time", text.removePrefix("\uFEFF").startsWith("time_iso,") && text.lines().size > 5)
    }

    @Test
    fun aWallClockSteppedForwardDoesNotCutTheGracePeriodShort() = runTest {
        val recorder = newRecorder()
        backgroundScope.launch { recorder.run() }
        engineRunning()
        advanceTimeBy(3_000)
        advanceTimeBy(15_000) // the rpm reading goes stale: the engine counts as off
        wallStepMs = 5 * HOUR_MS
        advanceTimeBy(AUTO_STOP_GRACE_MS - 30_000)
        assertTrue("still within the two minutes", recorder.state.value is RecordingState.Recording)
        advanceTimeBy(45_000)
        assertEquals(RecordingState.Idle, recorder.state.value)
    }

    /** Fails the first [failures] inserts with a bug-like exception, then works. */
    private class BuggyTripDao(private val rows: FakeTripDao, var failures: Int) : TripDao by rows {
        override suspend fun insert(trip: Trip): Long {
            if (failures-- > 0) throw IllegalStateException("bug in the insert")
            return rows.insert(trip)
        }
    }

    @Test
    fun anUnexpectedExceptionInATickDoesNotEndTheSamplingLoop() = runTest {
        val buggy = BuggyTripDao(dao, failures = 2)
        val recorder = newRecorder(buggy)
        val loop = backgroundScope.launch { recorder.run() }
        engineRunning()
        advanceTimeBy(1_500)
        assertTrue("the loop survived the failed starts", loop.isActive)
        assertEquals(RecordingState.Idle, recorder.state.value)

        advanceTimeBy(3_000)
        assertTrue("and records once the bug is over", recorder.state.value is RecordingState.Recording)
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
