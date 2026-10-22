package nl.stijnvdpol.klinedash.service

import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.geo.LapTimingState
import nl.stijnvdpol.klinedash.data.GateSpec
import nl.stijnvdpol.klinedash.data.GpsSettingsRepository
import nl.stijnvdpol.klinedash.data.Track
import nl.stijnvdpol.klinedash.data.TrackRepository
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.data.encodeSectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Sector lines lie in driving order, whatever order they were added in: lap detection counts them in
 * list order, so a list that is not in driving order loses every sector time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LapSectorOrderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tripDao = FakeTripDao()
    private val trackDao = FakeTrackDao()
    private val tracks = TrackRepository(trackDao, tripDao, GpsSettingsRepository(FakeSettingsDao(), Dispatchers.Unconfined))
    private val location = FakeLocationSource()
    private val liveLaps = FakeLiveLaps()
    private val tripsDir get() = File(folder.root, "trips").also { it.mkdirs() }
    private val analysis get() = TripAnalysisRepository(tripsDir, tripDao, trackDao, Dispatchers.Unconfined)

    private fun sessions(): LapSessions {
        val exporter = TripExporter(tripsDir, tripDao, analysis, MutableStateFlow(RecordingState.Idle), { TEST_BASE_MS }, Dispatchers.Unconfined)
        return LapSessions(tracks, tripDao, location, analysis, exporter, liveLaps) { TEST_BASE_MS }
    }

    /** The sector line across the circuit [distanceM] after the start line. */
    private fun sectorAt(distanceM: Double) = testCircuit.pointAt(distanceM).let { (point, heading) -> GateSpec(point.lat, point.lon, heading) }

    private fun distancesOf(sectors: List<GateSpec>) = sectors.map { spec ->
        (0..2_000 step 50).minBy { distance -> sectorAt(distance.toDouble()).let { abs(it.lat - spec.lat) + abs(it.lon - spec.lon) } }
    }

    /** The car is at [distanceM] on the circuit, driving at 72 km/h. */
    private fun driveTo(distanceM: Double) {
        val (point, heading) = testCircuit.pointAt(distanceM)
        location.fix.value = GeoFix(TEST_BASE_MS, point.lat, point.lon, 20.0, heading, 4.0, 12)
    }

    /** A complete lap, from 40 m before the line to 40 m after it. */
    private val completeLap = circuitFixes(72.0, 104_000)

    private fun timing(referenceLap: List<GeoFix>) =
        LapTimingState(true, 0L, emptyList(), null, null, 1, null, null, referenceLap)

    private suspend fun trackWith(vararg sectorDistances: Double): Track {
        val track = circuitTrack(sectors = sectorDistances.map(::sectorAt))
        return track.copy(id = tracks.add(track))
    }

    @Test
    fun aSectorEarlierInTheLapGoesBeforeAnExistingOneOnTheLatestCompleteLap() = runTest {
        val track = trackWith(1_200.0)
        liveLaps.live.value = LiveTripState(null, timing(completeLap), emptyList(), track.id)
        driveTo(500.0)

        val result = sessions().addSectorHere(track.id) as GateResult.Placed

        assertEquals(listOf(500, 1_200), distancesOf(result.track.sectors))
    }

    @Test
    fun aSectorLaterInTheLapStaysAfterTheExistingOnes() = runTest {
        val track = trackWith(500.0, 1_200.0)
        liveLaps.live.value = LiveTripState(null, timing(completeLap), emptyList(), track.id)
        driveTo(1_700.0)

        val result = sessions().addSectorHere(track.id) as GateResult.Placed

        assertEquals(listOf(500, 1_200, 1_700), distancesOf(result.track.sectors))
    }

    @Test
    fun withoutACompleteLapTheLapInProgressPlacesTheNewSectorAtItsHead() = runTest {
        val track = trackWith(300.0, 1_200.0)
        liveLaps.live.value = LiveTripState(null, timing(emptyList()), emptyList(), track.id)
        liveLaps.currentLap = circuitFixes(72.0, 38_000) // from 40 m before the line to 720 m
        driveTo(700.0)

        val result = sessions().addSectorHere(track.id) as GateResult.Placed

        assertEquals("after the line the lap crossed, before the one still ahead", listOf(300, 700, 1_200), distancesOf(result.track.sectors))
    }

    @Test
    fun withNoLapToMeasureOnTheSectorIsAppended() = runTest {
        val track = trackWith(1_200.0)
        driveTo(500.0)

        val result = sessions().addSectorHere(track.id) as GateResult.Placed

        assertEquals(listOf(1_200, 500), distancesOf(result.track.sectors))
    }

    @Test
    fun aLapOfAnotherTrackIsNotUsedToPlaceTheSector() = runTest {
        val track = trackWith(1_200.0)
        liveLaps.live.value = LiveTripState(null, timing(completeLap), emptyList(), track.id + 1)
        driveTo(500.0)

        val result = sessions().addSectorHere(track.id) as GateResult.Placed

        assertEquals(listOf(1_200, 500), distancesOf(result.track.sectors))
    }

    @Test
    fun theOrderIsStoredWithTheTrack() = runTest {
        val track = trackWith(1_200.0)
        liveLaps.live.value = LiveTripState(null, timing(completeLap), emptyList(), track.id)
        driveTo(500.0)
        sessions().addSectorHere(track.id)

        val stored = checkNotNull(tracks.get(track.id))
        assertEquals(listOf(500, 1_200), distancesOf(stored.sectors))
        assertEquals("what is stored is the ordered list", encodeSectors(stored.sectors), stored.sectorsJson)
    }

    @Test
    fun orderingATrackPutsItsSectorsInDrivingOrderAndLeavesAnOrderedOneAlone() = runTest {
        val track = trackWith(1_500.0, 300.0, 900.0)
        val sessions = sessions()

        sessions.orderSectors(track.id, completeLap)
        assertEquals(listOf(300, 900, 1_500), distancesOf(checkNotNull(tracks.get(track.id)).sectors))

        val json = checkNotNull(tracks.get(track.id)).sectorsJson
        sessions.orderSectors(track.id, completeLap)
        assertEquals("an ordered list is not written again", json, checkNotNull(tracks.get(track.id)).sectorsJson)
    }

    @Test
    fun aLapThatMissesASectorLeavesTheListAlone() = runTest {
        val track = trackWith(1_500.0, 300.0)
        val partial = circuitFixes(72.0, 38_000) // only reaches 720 m

        sessions().orderSectors(track.id, partial)

        assertEquals(listOf(1_500, 300), distancesOf(checkNotNull(tracks.get(track.id)).sectors))
    }

    /** The bug: lines stored in the order they were added (late one first) gave a lap no sector times at all. */
    @Test
    fun theSectorTimesComeBackOnceTheLinesAreInDrivingOrder() = runTest {
        val track = trackWith(1_200.0, 500.0)
        val trip = storeTripOn(track.id)
        val beforeOrdering = (analysis.analyze(trip.id) as AnalysisResult.Done).detail.laps
        assertTrue("a misordered list loses the sector times", beforeOrdering.isNotEmpty() && beforeOrdering.all { it.lap.sectorTimesMs.isEmpty() })

        sessions().orderSectors(track.id, completeLap)

        val laps = (analysis.analyze(trip.id) as AnalysisResult.Done).detail.laps
        assertTrue(laps.isNotEmpty())
        laps.forEach { lap ->
            assertEquals(3, lap.lap.sectorTimesMs.size)
            assertEquals(lap.lap.lapTimeMs, lap.lap.sectorTimesMs.sum())
            assertEquals(500.0 / 20.0 * 1_000, lap.lap.sectorTimesMs[0].toDouble(), 500.0)
            assertEquals(700.0 / 20.0 * 1_000, lap.lap.sectorTimesMs[1].toDouble(), 500.0)
        }
    }

    private suspend fun storeTripOn(trackId: Long): Trip {
        val fileName = "trip-20261022-140307.csv"
        File(tripsDir, fileName).writeText(tripCsvOf(circuitFixes(72.0, 320_000)), Charsets.UTF_8)
        val id = tripDao.insert(
            Trip(
                startMs = TEST_BASE_MS, endMs = TEST_BASE_MS + 320_000, fileName = fileName, sampleCount = 1_600, distanceKm = 6.4,
                maxRpm = 3000.0, maxSpeedKmh = 72.0, maxCoolantC = 90.0, trackId = trackId,
            ),
        )
        return checkNotNull(tripDao.get(id))
    }

    @Test
    fun aTrackThatBecomesActiveIsOrderedAgainstTheNewestTripWithACompleteLap() = runTest {
        val track = trackWith(1_200.0, 500.0)
        storeTripOn(track.id)
        val sessions = sessions()
        backgroundScope.launch { sessions.keepSectorsOrdered() }
        runCurrent()
        assertEquals("not active yet", listOf(1_200, 500), distancesOf(checkNotNull(tracks.get(track.id)).sectors))

        sessions.setActiveTrack(track.id)
        runCurrent()

        assertEquals(listOf(500, 1_200), distancesOf(checkNotNull(tracks.get(track.id)).sectors))
    }

    @Test
    fun aLapTheRunningTripCompletesOrdersTheLinesAppendedBeforeAnyLapWasKnown() = runTest {
        val track = trackWith(1_200.0, 500.0)
        val sessions = sessions()
        backgroundScope.launch { sessions.keepSectorsOrdered() }
        runCurrent()
        assertEquals(listOf(1_200, 500), distancesOf(checkNotNull(tracks.get(track.id)).sectors))

        liveLaps.live.value = LiveTripState(null, timing(completeLap), emptyList(), track.id)
        runCurrent()

        assertEquals(listOf(500, 1_200), distancesOf(checkNotNull(tracks.get(track.id)).sectors))
    }

    @Test
    fun withoutAnyRecordedLapATrackBecomingActiveIsLeftAlone() = runTest {
        val track = trackWith(1_200.0, 500.0)
        val sessions = sessions()
        backgroundScope.launch { sessions.keepSectorsOrdered() }

        sessions.setActiveTrack(track.id)
        runCurrent()

        assertEquals(listOf(1_200, 500), distancesOf(checkNotNull(tracks.get(track.id)).sectors))
    }
}
