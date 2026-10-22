package nl.stijnvdpol.klinedash.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.data.GpsSettingsRepository
import nl.stijnvdpol.klinedash.data.TrackRepository
import nl.stijnvdpol.klinedash.data.TripSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LapSessionsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tripDao = FakeTripDao()
    private val trackDao = FakeTrackDao()
    private val settingsDao = FakeSettingsDao()
    private val gpsSettings = GpsSettingsRepository(settingsDao)
    private val tracks = TrackRepository(trackDao, tripDao, gpsSettings)
    private val location = FakeLocationSource()

    private fun sessions(): LapSessions {
        val tripsDir = folder.newFolder("trips")
        val analysis = TripAnalysisRepository(tripsDir, tripDao, trackDao, Dispatchers.Unconfined)
        val exporter = TripExporter(tripsDir, tripDao, analysis, MutableStateFlow(RecordingState.Idle), { TEST_BASE_MS }, Dispatchers.Unconfined)
        return LapSessions(tracks, tripDao, location, analysis, exporter) { TEST_BASE_MS }
    }

    private val movingFix = GeoFix(TEST_BASE_MS, 51.0, 5.0, speedMps = 20.0, bearingDeg = 90.0, accuracyM = 4.0, satellites = 10)

    @Test
    fun startFinishHereStoresTheTrackAndMakesItActive() = runBlocking {
        location.fix.value = movingFix
        val sessions = sessions()

        val placed = sessions.startFinishHere("Home loop", widthM = 40.0) as GateResult.Placed

        assertEquals("Home loop", placed.track.name)
        assertEquals(90.0, placed.track.startHeadingDeg, 1e-9)
        assertEquals(40.0, placed.track.startWidthM, 0.0)
        assertEquals(51.0, placed.track.startLat, 1e-9)
        assertEquals(placed.track.id, sessions.activeTrack.first()?.id)
        assertEquals(listOf(placed.track.id), sessions.allTracks.first().map { it.id })
        assertTrue(placed.track.sectors.isEmpty())
    }

    @Test
    fun withoutAGoodFixNothingIsStored() = runBlocking {
        val sessions = sessions()
        location.fix.value = movingFix.copy(accuracyM = 60.0)
        val rejected = sessions.startFinishHere("Bad accuracy") as GateResult.Rejected
        assertTrue(rejected.reason.contains("accurate"))

        location.fix.value = movingFix
        location.isStale = true
        assertTrue(sessions.startFinishHere("Old fix") is GateResult.Rejected)

        assertTrue(trackDao.rows.value.isEmpty())
        assertNull(sessions.activeTrack.first())
    }

    @Test
    fun sectorLinesAreAddedInDrivingOrder() = runBlocking {
        val sessions = sessions()
        location.fix.value = movingFix
        val track = (sessions.startFinishHere("Loop") as GateResult.Placed).track

        location.fix.value = movingFix.copy(lat = 51.001, bearingDeg = 0.0)
        val first = (sessions.addSectorHere(track.id) as GateResult.Placed).track
        location.fix.value = movingFix.copy(lat = 51.002, bearingDeg = 180.0)
        val second = (sessions.addSectorHere(track.id) as GateResult.Placed).track

        assertEquals(1, first.sectors.size)
        assertEquals(listOf(51.001, 51.002), second.sectors.map { it.lat })
        assertEquals(2, second.lapGates().sectors.size)
    }

    @Test
    fun sectorOnAMissingTrackIsRefused() = runBlocking {
        location.fix.value = movingFix
        assertTrue(sessions().addSectorHere(42) is GateResult.Rejected)
    }

    @Test
    fun deletingTheActiveTrackSwitchesLapTimingOffAndKeepsTheTrips() = runBlocking {
        val sessions = sessions()
        location.fix.value = movingFix
        val track = (sessions.startFinishHere("Loop") as GateResult.Placed).track
        val tripId = tripDao.insert(
            nl.stijnvdpol.klinedash.data.Trip(
                startMs = 1, endMs = 2, fileName = "t.csv", sampleCount = 1, distanceKm = 0.0, maxRpm = null,
                maxSpeedKmh = null, maxCoolantC = null, trackId = track.id, lapCount = 3, bestLapMs = 50_000,
            ),
        )

        sessions.deleteTrack(track.id)

        assertNull(sessions.activeTrack.first())
        assertTrue(trackDao.rows.value.isEmpty())
        val trip = checkNotNull(tripDao.get(tripId))
        assertNull(trip.trackId)
        assertNull(trip.lapCount)
        assertNull(trip.bestLapMs)
    }

    @Test
    fun aTrackFromARecordedTraceIsNotActiveUntilChosen() = runBlocking {
        val sessions = sessions()
        val track = sessions.createTrack("From trace", circuitStartLine(), listOf(circuitStartLine()))
        assertNull(sessions.activeTrack.first())
        sessions.setActiveTrack(track.id)
        assertEquals(track.id, sessions.activeTrack.first()?.id)
        sessions.setActiveTrack(null)
        assertNull(sessions.activeTrack.first())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LocationHubTest {
    private fun TestScope.hub(use: MutableStateFlow<Boolean>, device: FakeLocationSource, simulated: FakeLocationSource) =
        LocationHub(device, simulated, use, backgroundScope)

    @Test
    fun usesTheDeviceGpsUnlessTheSimulationIsSelected() = runTest(UnconfinedTestDispatcher()) {
        val device = FakeLocationSource(usesDeviceGps = true)
        val simulated = FakeLocationSource(usesDeviceGps = false)
        val use = MutableStateFlow(false)
        val hub = hub(use, device, simulated)
        device.fix.value = GeoFix(1, 51.0, 5.0)
        simulated.fix.value = GeoFix(2, 52.0, 6.0)
        runCurrent()

        assertEquals(1L, hub.fix.value?.timeMs)
        assertTrue(hub.usesDeviceGps)
        use.value = true
        runCurrent()
        assertEquals(2L, hub.fix.value?.timeMs)
        assertFalse(hub.usesDeviceGps)
    }

    @Test
    fun switchingWhileRunningMovesTheUpdatesToTheOtherSource() = runTest(UnconfinedTestDispatcher()) {
        val device = FakeLocationSource(usesDeviceGps = true)
        val simulated = FakeLocationSource(usesDeviceGps = false)
        val use = MutableStateFlow(false)
        val hub = hub(use, device, simulated)

        hub.start()
        assertTrue(device.isRunning)
        use.value = true
        runCurrent()
        assertFalse("the device GPS is released", device.isRunning)
        assertTrue(simulated.isRunning)

        hub.stop()
        assertFalse(simulated.isRunning)
        use.value = false
        runCurrent()
        assertFalse("stopped means stopped, whatever is selected", device.isRunning)
    }
}

class TripSettingsPersistenceTest {
    @Test
    fun theNewOptionsDefaultToOffAndSurviveARoundTrip() = runBlocking {
        val repository = TripSettingsRepository(FakeSettingsDao())
        assertEquals(nl.stijnvdpol.klinedash.data.TripSettings(), repository.settings.first())
        repository.update { it.copy(autoStartWhenMoving = true) }
        assertTrue(repository.settings.first().autoStartWhenMoving)
        assertTrue("the old option is untouched", repository.settings.first().autoRecord)
    }

    @Test
    fun gpsSettingsKeepTheActiveTrackAndTheSimulationOptIn() = runBlocking {
        val repository = GpsSettingsRepository(FakeSettingsDao())
        assertEquals(nl.stijnvdpol.klinedash.data.GpsSettings(), repository.settings.first())
        assertFalse(repository.settings.first().useSimulatedGps)
        repository.update { it.copy(activeTrackId = 7, useSimulatedGps = true) }
        assertEquals(7L, repository.settings.first().activeTrackId)
        repository.update { it.copy(activeTrackId = null) }
        assertNull(repository.settings.first().activeTrackId)
    }
}
