package nl.stijnvdpol.klinedash.service

import android.content.ContentResolver
import android.database.SQLException
import java.io.File
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.data.TripDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val DAY_MS = 86_400_000L
private const val FIRST_DAY_MS = 1_792_677_787_000L

class TripRetentionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dao = FakeTripDao()
    private val recording = MutableStateFlow<RecordingState>(RecordingState.Idle)
    private lateinit var tripsDir: File

    @Before
    fun createFolder() {
        tripsDir = folder.newFolder("trips")
    }

    private fun library(tripDao: TripDao = dao) =
        TripLibrary(tripDao, tripsDir, recording, object : ContentResolver(null) {}, Dispatchers.Unconfined)

    /** Trip number [day] (a later day is newer) with its CSV on disk; [endMs] null makes it an unfinished one. */
    private suspend fun storeTrip(
        day: Int,
        endMs: Long? = FIRST_DAY_MS + day * DAY_MS + 1_000,
        trackId: Long? = null,
        lapCount: Int? = null,
    ): Trip {
        val startMs = FIRST_DAY_MS + day * DAY_MS
        val fileName = tripFileName(startMs, ZoneOffset.UTC)
        File(tripsDir, fileName).writeText("time_iso\n")
        val id = dao.insert(
            Trip(
                startMs = startMs, endMs = endMs, fileName = fileName, sampleCount = 1, distanceKm = 1.0,
                maxRpm = null, maxSpeedKmh = null, maxCoolantC = null, trackId = trackId, lapCount = lapCount,
            ),
        )
        return checkNotNull(dao.get(id))
    }

    private fun exists(trip: Trip) = File(tripsDir, trip.fileName).exists()

    private fun remainingDays() = dao.rows.value.map { (it.startMs - FIRST_DAY_MS) / DAY_MS }.sorted()

    @Test
    fun theOldestTripsBeyondTheLimitAreRemovedAndTheNewestKept() = runBlocking {
        val trips = (0 until 6).map { storeTrip(it) }

        val removed = library().prune(keep = 4)

        assertEquals(2, removed)
        assertEquals(listOf(2L, 3L, 4L, 5L), remainingDays())
        assertFalse(exists(trips[0]))
        assertFalse(exists(trips[1]))
        assertTrue(exists(trips[2]))
        assertTrue(exists(trips[5]))
    }

    @Test
    fun nothingIsRemovedWithinTheLimit() = runBlocking {
        (0 until 3).forEach { storeTrip(it) }

        assertEquals(0, library().prune(keep = 3))
        assertEquals(3, dao.rows.value.size)
        assertEquals(3, tripsDir.listFiles().orEmpty().size)
    }

    @Test
    fun theGeneratedFilesGoWithTheTripAndTheOtherTripsFilesStay() = runBlocking {
        val old = storeTrip(0)
        val new = storeTrip(1)
        for (trip in listOf(old, new)) {
            ExportFormat.entries.forEach { File(tripsDir, exportFileName(trip.fileName, it)).writeText("x") }
        }
        File(tripsDir, exportFileName(old.fileName, ExportFormat.VBO) + PARTIAL_FILE_SUFFIX).writeText("half")

        library().prune(keep = 1)

        assertEquals("only the newer trip is left, with its exports", 1 + ExportFormat.entries.size, tripsDir.listFiles().orEmpty().size)
        assertEquals(ExportFormat.entries.size, derivedFiles(tripsDir, new.fileName).size)
        assertTrue(derivedFiles(tripsDir, old.fileName).isEmpty())
    }

    @Test
    fun aTripThatIsBeingRecordedIsNeverRemoved() = runBlocking {
        storeTrip(0)
        val running = storeTrip(1, endMs = null)
        storeTrip(2)

        library().prune(keep = 0)

        assertEquals("an unfinished row is recording or waits for recovery", listOf(1L), remainingDays())
        assertTrue(exists(running))
    }

    @Test
    fun aTripTheRecorderReportsIsSkippedEvenWithAnEndTime() = runBlocking {
        val old = storeTrip(0)
        recording.value = RecordingState.Recording(old.startMs, 3, old.fileName, isAuto = true)

        assertEquals(0, library().prune(keep = 0))
        assertTrue(exists(old))
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun lapSessionsAreNeverRemovedAndDoNotCountTowardsTheLimit() = runBlocking {
        val lapSession = storeTrip(0, trackId = 1, lapCount = 5)
        val lapsNotCountedYet = storeTrip(1, trackId = 1, lapCount = null)
        storeTrip(2)
        storeTrip(3)
        storeTrip(4)

        library().prune(keep = 2)

        assertEquals("both lap sessions and the 2 newest plain trips", listOf(0L, 1L, 3L, 4L), remainingDays())
        assertTrue(exists(lapSession))
        assertTrue(exists(lapsNotCountedYet))
    }

    @Test
    fun aTrackWithoutLapsOrAForgottenTrackLeavesAPlainTrip() = runBlocking {
        storeTrip(0, trackId = 1, lapCount = 0)
        storeTrip(1, trackId = null, lapCount = null)
        storeTrip(2)

        library().prune(keep = 1)

        assertEquals(listOf(2L), remainingDays())
    }

    @Test
    fun aMissingFileStillRemovesTheRow() = runBlocking {
        val old = storeTrip(0)
        File(tripsDir, old.fileName).delete()
        storeTrip(1)

        assertEquals(1, library().prune(keep = 1))
        assertEquals(listOf(1L), remainingDays())
    }

    @Test
    fun aFileThatCannotBeRemovedKeepsItsRowAndTheRestIsStillPruned() = runBlocking {
        val stuck = storeTrip(0)
        // A folder with content cannot be deleted like a file: it stands in for a locked or read-only file.
        val stuckFile = File(tripsDir, stuck.fileName)
        stuckFile.delete()
        stuckFile.mkdir()
        File(stuckFile, "inside").writeText("x")
        val other = storeTrip(1)
        storeTrip(2)

        val removed = library().prune(keep = 1)

        assertEquals(1, removed)
        assertEquals("the row of the stuck file stays, nothing is orphaned", listOf(0L, 2L), remainingDays())
        assertFalse(exists(other))
    }

    @Test
    fun manyTripsAreRemovedAcrossSeveralBatches() = runBlocking {
        (0 until 250).forEach { storeTrip(it) }

        assertEquals(240, library().prune(keep = 10))
        assertEquals((240L until 250L).toList(), remainingDays())
        assertEquals(10, tripsDir.listFiles().orEmpty().size)
    }

    @Test
    fun aDatabaseErrorIsLoggedAndDoesNotReachTheCaller() = runBlocking {
        storeTrip(0)
        val failing = object : TripDao by dao {
            override suspend fun getBeyondRetention(keep: Int, limit: Int): List<Trip> = throw SQLException("disk I/O error")
        }

        assertEquals(0, library(failing).prune(keep = 0))
        assertEquals(1, dao.rows.value.size)
    }
}
