package nl.stijnvdpol.klinedash.service

import java.io.File
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.obd.ObdPoller
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.data.GpsSettingsRepository
import nl.stijnvdpol.klinedash.data.TrackRepository
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.data.TripSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A lap session ends with its trip, and the active track goes with it, in the service layer: these
 * tests have a recorder and the repositories but no screen and no ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveTrackReleaseTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tripDao = FakeTripDao()
    private val trackDao = FakeTrackDao()
    private val gpsSettings = GpsSettingsRepository(FakeSettingsDao())
    private val tracks = TrackRepository(trackDao, tripDao, gpsSettings)
    private val poller = FakePoller()
    private val pollerFlow = MutableStateFlow<ObdPoller?>(poller)
    private val settings = MutableStateFlow(TripSettings(autoRecord = false))
    private val tripsDir get() = File(folder.root, "trips")

    private suspend fun activeTrackId(): Long? = gpsSettings.settings.first().activeTrackId

    /** A track that is active, as "Start lap session" or USE under GPS & Tracks leave it. */
    private suspend fun activateTrack(): Long {
        val id = trackDao.insert(circuitTrack(id = 0))
        tracks.setActive(id)
        return id
    }

    private fun TestScope.recorder() = TripRecorder(
        tripsDir = tripsDir, tripDao = tripDao, settings = settings, poller = pollerFlow,
        clock = { TEST_BASE_MS + currentTime }, zone = ZoneOffset.UTC, ioDispatcher = StandardTestDispatcher(testScheduler),
        activeTrack = tracks.activeTrack, releaseTrack = tracks::release,
    )

    private fun TestScope.engineRunning() {
        val now = TEST_BASE_MS + currentTime
        poller.readings.value = mapOf(
            "rpm" to Reading("rpm", 900.0, now),
            "speed" to Reading("speed", 36.0, now),
            "coolant" to Reading("coolant", 88.0, now),
        )
    }

    /** A recorder that runs with the active track applied, and a trip on that track that has taken a few samples. */
    private suspend fun TestScope.recordingLapSession(): Pair<TripRecorder, Job> {
        val trackId = activateTrack()
        val recorder = recorder()
        val job = backgroundScope.launch { recorder.run() }
        runCurrent()
        engineRunning()
        assertEquals(StartResult.Started, recorder.start())
        advanceTimeBy(3_000)
        assertEquals("the trip is timed on the active track", trackId, recorder.live.value.trackId)
        return recorder to job
    }

    @Test
    fun stoppingTheTripReleasesTheTrack() = runTest {
        val (recorder, _) = recordingLapSession()
        assertEquals("still active while the lap session runs", 1L, activeTrackId())

        recorder.stop()

        assertNull(activeTrackId())
    }

    @Test
    fun theIgnitionGoingOffReleasesTheTrack() = runTest {
        val (recorder, _) = recordingLapSession()

        recorder.finishForIgnitionOff()

        assertNull(activeTrackId())
    }

    @Test
    fun theLinkBeingLostEndsTheTripAndReleasesTheTrack() = runTest {
        settings.value = TripSettings(autoRecord = true)
        trackDao.insert(circuitTrack(id = 0))
        tracks.setActive(1)
        val recorder = recorder()
        backgroundScope.launch { recorder.run() }
        runCurrent()
        engineRunning()
        advanceTimeBy(3_000)
        assertTrue("the engine started an automatic trip", recorder.state.value is RecordingState.Recording)
        assertEquals(1L, activeTrackId())

        pollerFlow.value = null
        advanceTimeBy(AUTO_STOP_GRACE_MS + 15_000)

        assertEquals(RecordingState.Idle, recorder.state.value)
        assertNull(activeTrackId())
    }

    @Test
    fun theServiceStoppingReleasesTheTrack() = runTest {
        val (recorder, job) = recordingLapSession()

        job.cancelAndJoin()

        assertEquals(RecordingState.Idle, recorder.state.value)
        assertNull(activeTrackId())
    }

    @Test
    fun theNextTripIsAPlainOne() = runTest {
        val (recorder, _) = recordingLapSession()
        recorder.stop()
        runCurrent()

        assertEquals(StartResult.Started, recorder.start())
        assertNull("no track is active, so the new trip is not timed", recorder.live.value.trackId)
        recorder.stop()
    }

    @Test
    fun aTrackChosenMidTripIsReleasedWithTheTrip() = runTest {
        val recorder = recorder()
        backgroundScope.launch { recorder.run() }
        runCurrent()
        engineRunning()
        assertEquals(StartResult.Started, recorder.start())
        advanceTimeBy(2_000)
        trackDao.insert(circuitTrack(id = 0))
        tracks.setActive(1)
        runCurrent()
        assertEquals("a track chosen mid-trip is timed from then on", 1L, recorder.live.value.trackId)

        recorder.stop()

        assertNull(activeTrackId())
    }

    @Test
    fun releasingATrackThatIsNoLongerActiveLeavesTheActiveOneAlone() = runBlocking {
        val first = trackDao.insert(circuitTrack(id = 0))
        val second = trackDao.insert(circuitTrack(id = 0))
        tracks.setActive(second)

        tracks.release(first)
        assertEquals(second, activeTrackId())

        tracks.release(second)
        assertNull(activeTrackId())
    }

    @Test
    fun aLapSessionCutOffByThePowerIsReleasedWhenTheTripIsRecovered() = runBlocking {
        val trackId = activateTrack()
        tripsDir.mkdirs()
        val fileName = "trip-20261022-140307.csv"
        File(tripsDir, fileName).writeText(tripCsvOf(circuitFixes(72.0, 60_000)), Charsets.UTF_8)
        tripDao.insert(
            Trip(
                startMs = TEST_BASE_MS, endMs = null, fileName = fileName, sampleCount = 0, distanceKm = 0.0,
                maxRpm = null, maxSpeedKmh = null, maxCoolantC = null, trackId = trackId,
            ),
        )

        TripRecovery(tripsDir, tripDao, Dispatchers.Unconfined, releaseTrack = tracks::release).recover(activeTripId = null)

        assertNull(activeTrackId())
    }
}
