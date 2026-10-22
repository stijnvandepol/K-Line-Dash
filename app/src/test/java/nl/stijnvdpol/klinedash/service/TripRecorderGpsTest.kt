package nl.stijnvdpol.klinedash.service

import java.io.File
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.geo.Geodesy
import nl.stijnvdpol.klinedash.core.geo.LatLon
import nl.stijnvdpol.klinedash.core.obd.ObdPoller
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.trip.readTripCsv
import nl.stijnvdpol.klinedash.data.GateSpec
import nl.stijnvdpol.klinedash.data.Track
import nl.stijnvdpol.klinedash.data.TripSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The recorder with a position source: the simulated circuit driven at the speed the (fake) poller
 * reports, as the simulator transport does in the app, and a hand-moved fake source.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripRecorderGpsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tripDao = FakeTripDao()
    private val trackDao = FakeTrackDao()
    private val poller = FakePoller()
    private val pollerFlow = MutableStateFlow<ObdPoller?>(poller)
    private val settings = MutableStateFlow(TripSettings(autoRecord = false))
    private val activeTrack = MutableStateFlow<Track?>(null)
    private val tripsDir get() = File(folder.root, "trips")

    private fun TestScope.recorder(location: LocationSource, poller: MutableStateFlow<ObdPoller?> = pollerFlow): TripRecorder {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val analysis = TripAnalysisRepository(tripsDir, tripDao, trackDao, dispatcher)
        return TripRecorder(
            tripsDir = tripsDir, tripDao = tripDao, settings = settings, poller = poller,
            clock = { TEST_BASE_MS + currentTime }, zone = ZoneOffset.UTC, ioDispatcher = dispatcher,
            location = location, activeTrack = activeTrack, refreshSummary = { analysis.refreshSummary(it) },
        )
    }

    /** The vehicle speed reading the simulator would deliver, kept fresh. */
    private fun TestScope.driveAt(speedKmh: Double) {
        val now = TEST_BASE_MS + currentTime
        poller.readings.value = mapOf(
            "rpm" to Reading("rpm", 3000.0, now),
            "speed" to Reading("speed", speedKmh, now),
            "coolant" to Reading("coolant", 90.0, now),
        )
    }

    @Test
    fun aTripOnTheSimulatedCircuitRecordsPositionsAndTimesTheLaps() = runTest {
        val source = SimulatedLocationSource(pollerFlow, backgroundScope, clock = { TEST_BASE_MS + currentTime })
        trackDao.insert(circuitTrack(id = 0))
        activeTrack.value = trackDao.get(1)
        val recorder = recorder(source)
        backgroundScope.launch { recorder.run() }
        source.start()
        runCurrent()
        assertTrue("the simulated source demands the speed signal", "speed" in poller.demands.getValue("gps-sim"))

        driveAt(72.0)
        runCurrent()
        assertEquals(StartResult.Started, recorder.start())
        repeat(115) {
            driveAt(72.0)
            advanceTimeBy(1_000)
        }

        val live = recorder.live.value
        assertEquals("one full 2 km lap at 20 m/s", 1, live.lapStats.size)
        assertEquals(100_000.0, live.lapStats.single().lap.lapTimeMs.toDouble(), 500.0)
        assertEquals(1, live.lapTiming?.lapCount)
        assertEquals(1L, live.trackId)
        assertNotNull(live.stats)
        assertTrue("distance by GPS", checkNotNull(live.stats).gpsDistanceKm > 2.0)

        recorder.stop()
        val trip = tripDao.rows.value.single()
        assertEquals(1L, trip.trackId)
        assertTrue(trip.hasGps)
        assertEquals("recomputed from the CSV at the end", 1, trip.lapCount)
        assertEquals(100_000.0, checkNotNull(trip.bestLapMs).toDouble(), 500.0)
        assertEquals(LiveTripState.Idle, recorder.live.value)

        val lines = File(tripsDir, trip.fileName).readText(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        assertTrue(lines.first().endsWith("lat (deg),lon (deg),gps_speed (km/h),heading (deg),gps_acc (m),sats,gps_age (ms)"))
        assertTrue("5 Hz rows from the 5 Hz fixes, not 1 Hz: ${lines.size}", lines.size > 400)
        val csv = File(tripsDir, trip.fileName).bufferedReader(Charsets.UTF_8).use { readTripCsv(it).samples.toList() }
        val fixes = csv.mapNotNull { it.gps }
        assertTrue(fixes.size > 400)
        assertEquals(72.0, checkNotNull(fixes.last().speedMps) * 3.6, 0.2)
    }

    @Test
    fun aStaleFixLeavesTheGpsCellsEmpty() = runTest {
        val location = FakeLocationSource()
        location.fix.value = GeoFix(TEST_BASE_MS, 51.0, 5.0, 15.0, 90.0, 4.0, 10)
        val recorder = recorder(location)
        backgroundScope.launch { recorder.run() }
        assertEquals(StartResult.Started, recorder.start())
        driveAt(50.0)
        advanceTimeBy(3_000)
        location.isStale = true
        advanceTimeBy(3_000)
        recorder.stop()

        val trip = tripDao.rows.value.single()
        val rows = File(tripsDir, trip.fileName).bufferedReader(Charsets.UTF_8).use { readTripCsv(it).samples.toList() }
        assertTrue(rows.first().gps != null)
        assertNull(rows.last().gps)
    }

    @Test
    fun aPhoneWithoutAnAdapterStartsWhenMovingAndStopsAfterTheGrace() = runTest {
        settings.value = TripSettings(autoRecord = true, autoStartWhenMoving = true)
        val location = FakeLocationSource()
        val recorder = recorder(location, MutableStateFlow(null))
        assertEquals("no link and no fix yet", StartResult.NotConnected, recorder.start())
        backgroundScope.launch { recorder.run() }

        location.fix.value = GeoFix(TEST_BASE_MS, 51.0, 5.0, 2.0, 90.0, 4.0, 10)
        advanceTimeBy(8_000)
        assertEquals("walking pace does not start a trip", RecordingState.Idle, recorder.state.value)

        location.fix.value = location.fix.value?.copy(speedMps = 15.0)
        advanceTimeBy(4_000)
        assertEquals("above 10 km/h for less than 5 s", RecordingState.Idle, recorder.state.value)
        advanceTimeBy(2_000)
        val recording = recorder.state.value as RecordingState.Recording
        assertTrue(recording.isAuto)

        location.fix.value = location.fix.value?.copy(speedMps = 0.0)
        advanceTimeBy(AUTO_STOP_GRACE_MS - 5_000)
        assertTrue("standing still for less than the grace", recorder.state.value is RecordingState.Recording)
        advanceTimeBy(15_000)
        assertEquals(RecordingState.Idle, recorder.state.value)
        assertTrue(tripDao.rows.value.single().endMs != null)
    }

    @Test
    fun movingDoesNotStartATripWhenTheOptionIsOff() = runTest {
        settings.value = TripSettings(autoRecord = true, autoStartWhenMoving = false)
        val location = FakeLocationSource()
        location.fix.value = GeoFix(TEST_BASE_MS, 51.0, 5.0, 30.0, 90.0, 4.0, 10)
        val recorder = recorder(location, MutableStateFlow(null))
        backgroundScope.launch { recorder.run() }
        advanceTimeBy(20_000)
        assertEquals(RecordingState.Idle, recorder.state.value)
    }

    @Test
    fun aTrackSetWhileRecordingStartsTheLapTimerAndIsStoredWithTheTrip() = runTest {
        val location = FakeLocationSource()
        location.fix.value = GeoFix(TEST_BASE_MS, 51.0, 5.0, 20.0, 90.0, 4.0, 10)
        val recorder = recorder(location)
        backgroundScope.launch { recorder.run() }
        driveAt(72.0)
        assertEquals(StartResult.Started, recorder.start())
        advanceTimeBy(3_000)
        assertNull(recorder.live.value.lapTiming)
        assertNull(tripDao.rows.value.single().trackId)

        trackDao.insert(circuitTrack(id = 0))
        activeTrack.value = trackDao.get(1)
        advanceTimeBy(1_000)
        assertEquals(1L, tripDao.rows.value.single().trackId)
        assertNotNull(recorder.live.value.lapTiming)

        activeTrack.value = null
        advanceTimeBy(1_000)
        assertEquals("ending the lap session keeps the trip's track", 1L, tripDao.rows.value.single().trackId)
        recorder.stop()
    }

    @Test
    fun theTrackActiveAtStartBelongsToTheTrip() = runTest {
        trackDao.insert(circuitTrack(id = 0))
        activeTrack.value = trackDao.get(1)
        val recorder = recorder(NoLocation)
        backgroundScope.launch { recorder.run() }
        runCurrent()
        assertEquals(StartResult.Started, recorder.start())
        assertEquals(1L, tripDao.rows.value.single().trackId)
        assertEquals(1L, recorder.live.value.trackId)
        recorder.stop()
    }

    @Test
    fun aLapSessionCutOffByThePowerGetsItsLapsWhenRecovered() = runTest {
        trackDao.insert(circuitTrack(id = 0))
        tripsDir.mkdirs()
        val fileName = "trip-20261022-140307.csv"
        // Three laps at 72 km/h, the last row cut off in the middle as a power loss leaves it.
        File(tripsDir, fileName).writeText(tripCsvOf(circuitFixes(72.0, 320_000)).dropLast(5), Charsets.UTF_8)
        tripDao.insert(
            nl.stijnvdpol.klinedash.data.Trip(
                startMs = TEST_BASE_MS, endMs = null, fileName = fileName, sampleCount = 0, distanceKm = 0.0,
                maxRpm = null, maxSpeedKmh = null, maxCoolantC = null, trackId = 1,
            ),
        )

        recorder(NoLocation).recoverUnfinished()

        val trip = tripDao.rows.value.single()
        assertNotNull(trip.endMs)
        assertEquals(3, trip.lapCount)
        assertEquals(100_000.0, checkNotNull(trip.bestLapMs).toDouble(), 500.0)
    }

    @Test
    fun addingASectorLineWhileTimingKeepsTheLapClockRunning() = runTest {
        val source = SimulatedLocationSource(pollerFlow, backgroundScope, clock = { TEST_BASE_MS + currentTime })
        trackDao.insert(circuitTrack(id = 0))
        activeTrack.value = trackDao.get(1)
        val recorder = recorder(source)
        backgroundScope.launch { recorder.run() }
        source.start()
        driveAt(72.0)
        runCurrent()
        assertEquals(StartResult.Started, recorder.start())
        repeat(30) {
            driveAt(72.0)
            advanceTimeBy(1_000)
        }
        val elapsedBefore = checkNotNull(recorder.live.value.lapTiming?.currentLapElapsedMs)
        assertTrue(elapsedBefore > 20_000)

        val sector = testCircuit.pointAt(1_000.0).let { (point, heading) -> GateSpec(point.lat, point.lon, heading) }
        activeTrack.value = circuitTrack(id = 1, sectors = listOf(sector))
        driveAt(72.0)
        advanceTimeBy(1_000)

        val timing = checkNotNull(recorder.live.value.lapTiming)
        assertTrue("the lap clock went on: ${timing.currentLapElapsedMs}", checkNotNull(timing.currentLapElapsedMs) > elapsedBefore)
        recorder.stop()
    }

    @Test
    fun switchingTheLocationSourceAddsNoDistanceBetweenTheTwo() = runTest {
        val location = FakeLocationSource(usesDeviceGps = true)
        val recorder = recorder(location)
        backgroundScope.launch { recorder.run() }
        var northM = 0.0
        fun moveTo(step: Int) {
            val at = Geodesy.destination(LatLon(51.0, 5.0), 0.0, northM)
            location.fix.value = GeoFix(TEST_BASE_MS + step * 1_000L, at.lat, at.lon, 25.0, 0.0, 4.0, 10)
            driveAt(90.0)
        }
        moveTo(0)
        assertEquals(StartResult.Started, recorder.start())
        for (step in 1..11) {
            advanceTimeBy(1_000)
            // At step 6 the other source takes over 70 m on: a plausible step at 90 km/h, but not driven.
            northM += if (step == 6) 70.0 else 25.0
            if (step == 6) location.usesDeviceGps = false
            moveTo(step)
        }
        advanceTimeBy(1_000)

        val stats = checkNotNull(recorder.live.value.stats)
        assertEquals(northM - 70.0, stats.gpsDistanceKm * 1_000.0, 1.0)
        recorder.stop()
    }
}
