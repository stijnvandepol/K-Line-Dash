package nl.stijnvdpol.klinedash.service

import android.content.ContentResolver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import nl.stijnvdpol.klinedash.core.geo.Geodesy
import nl.stijnvdpol.klinedash.data.GateSpec
import nl.stijnvdpol.klinedash.data.GpsSettingsRepository
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.data.TrackRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Analysis and export of recorded trips, against real files in a temporary trips folder. */
class TripExportTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tripDao = FakeTripDao()
    private val trackDao = FakeTrackDao()
    private val recording = MutableStateFlow<RecordingState>(RecordingState.Idle)
    private lateinit var tripsDir: File
    private lateinit var analysis: TripAnalysisRepository
    private lateinit var exporter: TripExporter

    @Before
    fun setUp() {
        tripsDir = folder.newFolder("trips")
        analysis = TripAnalysisRepository(tripsDir, tripDao, trackDao, Dispatchers.Unconfined)
        exporter = TripExporter(tripsDir, tripDao, analysis, recording, { TEST_BASE_MS }, Dispatchers.Unconfined)
    }

    /** A finished 3-lap drive on the circuit, stored like the recorder does; [withGps] false = an OBD-only trip. */
    private suspend fun storeTrip(withGps: Boolean = true, trackId: Long? = null): Trip {
        val fileName = "trip-20261022-140307.csv"
        val fixes = circuitFixes(speedKmh = 144.0, durationMs = 160_000) // 40 m/s: 50 s per lap
        File(tripsDir, fileName).writeText("\uFEFF" + tripCsvOf(fixes, withGps), Charsets.UTF_8)
        val id = tripDao.insert(
            Trip(
                startMs = TEST_BASE_MS, endMs = TEST_BASE_MS + 160_000, fileName = fileName, sampleCount = fixes.size,
                distanceKm = 6.4, maxRpm = 3000.0, maxSpeedKmh = 144.0, maxCoolantC = 90.0, trackId = trackId,
            ),
        )
        return checkNotNull(tripDao.get(id))
    }

    @Test
    fun analysisGivesStatsLapsAndADrawableRoute() = runBlocking {
        trackDao.insert(circuitTrack(id = 0))
        val trip = storeTrip(trackId = 1)

        val detail = (analysis.analyze(trip.id) as AnalysisResult.Done).detail

        assertEquals(3, detail.laps.size)
        detail.laps.forEach { assertEquals(50_000.0, it.lap.lapTimeMs.toDouble(), 300.0) }
        assertTrue(detail.hasGps)
        assertNotNull(detail.stats)
        assertTrue(checkNotNull(detail.stats).gpsDistanceKm > 6.0)
        assertTrue("800 fixes fit the budget unchanged", detail.route.size in 100..MAX_ROUTE_POINTS)
        assertEquals(1L, detail.track?.id)
    }

    @Test
    fun aLongRouteIsDecimatedForDrawing() = runBlocking {
        val fileName = "trip-long.csv"
        val fixes = circuitFixes(speedKmh = 144.0, durationMs = 900_000, intervalMs = 100) // 9000 fixes, 18 laps
        File(tripsDir, fileName).writeText(tripCsvOf(fixes), Charsets.UTF_8)
        val id = tripDao.insert(
            Trip(startMs = TEST_BASE_MS, endMs = null, fileName = fileName, sampleCount = fixes.size, distanceKm = 0.0, maxRpm = null, maxSpeedKmh = null, maxCoolantC = null),
        )

        val detail = (analysis.analyze(id) as AnalysisResult.Done).detail

        assertTrue(detail.route.size <= MAX_ROUTE_POINTS)
        assertTrue(detail.laps.isEmpty())
        assertEquals(null, detail.track)
    }

    @Test
    fun aStrayFarFixDoesNotStretchTheRoute() = runBlocking {
        val fileName = "trip-stray.csv"
        val fixes = circuitFixes(speedKmh = 72.0, durationMs = 60_000)
        val stray = fixes[100].copy(lat = fixes[100].lat + 0.4, lon = fixes[100].lon + 0.4)
        val withStrays = listOf(stray.copy(timeMs = fixes.first().timeMs - 1_000)) + fixes.take(100) + stray + fixes.drop(101)
        File(tripsDir, fileName).writeText(tripCsvOf(withStrays), Charsets.UTF_8)
        val id = tripDao.insert(
            Trip(startMs = TEST_BASE_MS, endMs = null, fileName = fileName, sampleCount = withStrays.size, distanceKm = 0.0, maxRpm = null, maxSpeedKmh = null, maxCoolantC = null),
        )

        val detail = (analysis.analyze(id) as AnalysisResult.Done).detail

        assertTrue(detail.hasGps)
        val origin = testCircuit.pointAt(0.0).first
        val farthestM = detail.route.maxOf { Geodesy.distanceM(origin, it) }
        assertTrue("the route reaches $farthestM m from the circuit", farthestM < 1_000.0)
        assertEquals("the stray first fix is gone", 0.0, Geodesy.distanceM(fixes.first().position, detail.route.first()), 1.0)
    }

    @Test
    fun missingTripOrFileIsReportedNotThrown() = runBlocking {
        assertEquals(AnalysisResult.TripNotFound, analysis.analyze(99))
        val id = tripDao.insert(Trip(startMs = 1, endMs = 2, fileName = "gone.csv", sampleCount = 1, distanceKm = 0.0, maxRpm = null, maxSpeedKmh = null, maxCoolantC = null))
        assertTrue(analysis.analyze(id) is AnalysisResult.Failed)
        assertEquals(ExportResult.Failed("The trip file is missing."), exporter.exportTrip(id))
    }

    @Test
    fun refreshingTheSummaryFillsTheTripListCache() = runBlocking {
        trackDao.insert(circuitTrack(id = 0))
        val trip = storeTrip(trackId = 1)

        analysis.refreshSummary(trip.id)

        val row = checkNotNull(tripDao.get(trip.id))
        assertEquals(3, row.lapCount)
        assertEquals(50_000.0, checkNotNull(row.bestLapMs).toDouble(), 300.0)
        assertTrue(row.hasGps)
        assertNotNull(row.movingTimeMs)
    }

    @Test
    fun exportWritesTheFourFilesNextToTheTripAndTheServerListsThem() = runBlocking {
        trackDao.insert(circuitTrack(id = 0))
        val trip = storeTrip(trackId = 1)

        val result = exporter.exportTrip(trip.id) as ExportResult.Done

        assertEquals(
            listOf("trip-20261022-140307.vbo", "trip-20261022-140307.gpx", "trip-20261022-140307.pc.csv", "trip-20261022-140307.laps.csv"),
            result.files.map { it.name },
        )
        assertTrue(result.skipped.isEmpty())
        val vbo = File(tripsDir, "trip-20261022-140307.vbo").readBytes().toString(Charsets.UTF_8)
        assertTrue("VBO uses CR+LF", vbo.startsWith("File created on 22/10/2026 at 14:03:07\r\n"))
        assertFalse("no bare line feeds in the VBO", vbo.replace("\r\n", "").contains('\n'))
        assertTrue(File(tripsDir, "trip-20261022-140307.gpx").readText().startsWith("<?xml"))
        val pcBytes = File(tripsDir, "trip-20261022-140307.pc.csv").readBytes()
        assertEquals("UTF-8 byte order mark for Excel", listOf(0xEF, 0xBB, 0xBF), pcBytes.take(3).map { it.toInt() and 0xFF })
        val pcHeader = pcBytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").lines().first()
        assertTrue(pcHeader, pcHeader.startsWith("time_iso,elapsed_s,lap,"))
        val lapRows = File(tripsDir, "trip-20261022-140307.laps.csv").readText().removePrefix("\uFEFF").lines().filter { it.isNotEmpty() }
        assertEquals("header and three laps", 4, lapRows.size)

        val offered = listTripFiles(tripsDir).map { it.name }.toSet()
        assertTrue("the Wi-Fi export lists the derived files", offered.containsAll(result.files.map { it.name }))
        assertTrue("trip-20261022-140307.csv" in offered)
        assertTrue("no half-written files are offered", offered.none { it.endsWith(PARTIAL_FILE_SUFFIX) })
        assertEquals(4, derivedFiles(tripsDir, trip.fileName).size)
    }

    @Test
    fun aTripWithoutGpsOrLapsSkipsTheFormatsThatNeedThem() = runBlocking {
        val trip = storeTrip(withGps = false)

        val result = exporter.exportTrip(trip.id) as ExportResult.Done

        assertEquals(listOf("trip-20261022-140307.pc.csv"), result.files.map { it.name })
        assertEquals(setOf(ExportFormat.VBO, ExportFormat.GPX, ExportFormat.LAPS_CSV), result.skipped.map { it.format }.toSet())
        assertTrue(result.skipped.first { it.format == ExportFormat.VBO }.reason.contains("no GPS"))
        assertTrue(result.skipped.first { it.format == ExportFormat.LAPS_CSV }.reason.contains("laps"))
    }

    @Test
    fun onlyTheRequestedFormatsAreWrittenAndAnExportIsRewritten() = runBlocking {
        val trip = storeTrip()

        val first = exporter.exportTrip(trip.id, setOf(ExportFormat.GPX)) as ExportResult.Done
        assertEquals(listOf("trip-20261022-140307.gpx"), first.files.map { it.name })
        File(tripsDir, "trip-20261022-140307.gpx").writeText("old")

        exporter.exportTrip(trip.id, setOf(ExportFormat.GPX))

        assertTrue(File(tripsDir, "trip-20261022-140307.gpx").readText().startsWith("<?xml"))
    }

    @Test
    fun aRecordingTripIsNotExported() = runBlocking {
        val trip = storeTrip()
        recording.value = RecordingState.Recording(TEST_BASE_MS, 5, trip.fileName, isAuto = false)
        assertEquals(ExportResult.IsRecording, exporter.exportTrip(trip.id))
    }

    @Test
    fun deletingATripRemovesItsExportsToo() = runBlocking {
        val trip = storeTrip()
        exporter.exportTrip(trip.id)
        val library = TripLibrary(tripDao, tripsDir, recording, object : ContentResolver(null) {}, Dispatchers.Unconfined)

        assertEquals(DeleteResult.Deleted, library.delete(trip.id))

        assertTrue("nothing left of the trip", tripsDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun changingTheGatesRecomputesTheCacheAndDropsStaleExports() = runBlocking {
        val tracks = TrackRepository(trackDao, tripDao, GpsSettingsRepository(FakeSettingsDao()))
        val sessions = LapSessions(tracks, tripDao, NoLocation, analysis, exporter, FakeLiveLaps()) { TEST_BASE_MS }
        val track = sessions.createTrack("Circuit", circuitStartLine())
        val trip = storeTrip(trackId = track.id)
        analysis.refreshSummary(trip.id)
        exporter.exportTrip(trip.id)
        assertEquals(3, tripDao.get(trip.id)?.lapCount)

        // A narrower start line: the laps are still found, but the exports made for the old gate are stale.
        val other = circuitStartLine().let { GateSpec(it.lat, it.lon, it.headingDeg, widthM = 5.0) }
        sessions.setStartFinish(track.id, other)

        assertTrue("old exports are gone, the next export rewrites them", derivedFiles(tripsDir, trip.fileName).isEmpty())
        assertNotNull(tripDao.get(trip.id)?.lapCount)

        sessions.assignTrackToTrip(trip.id, null)
        assertEquals(null, tripDao.get(trip.id)?.trackId)
        assertEquals(null, tripDao.get(trip.id)?.lapCount)
    }

    @Test
    fun theMimeTypeFollowsTheExtension() {
        assertEquals("text/csv", mimeTypeOf("a.pc.csv"))
        assertEquals("application/gpx+xml", mimeTypeOf("a.gpx"))
        assertEquals("application/octet-stream", mimeTypeOf("a.vbo"))
        assertEquals("trip-1.laps.csv", exportFileName("trip-1.csv", ExportFormat.LAPS_CSV))
    }
}
