package nl.stijnvdpol.klinedash.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The retention query on a real SQLite database: the JVM tests run the same rule against a fake
 * DAO, so this is what proves the SQL picks the trips the Kotlin rule ([isLapSession]) means.
 * Deleting the wrong trips cannot be undone, so the edge cases (a running trip, a lap count not
 * known yet, NULL in SQL) are checked here.
 */
internal class TripRetentionQueryTest {

    private lateinit var database: AppDatabase
    private lateinit var trips: TripDao

    @Before
    fun openDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        trips = database.tripDao()
    }

    @After
    fun closeDatabase() = database.close()

    private suspend fun insert(startMs: Long, endMs: Long? = startMs + 1, trackId: Long? = null, lapCount: Int? = null): Long =
        trips.insert(
            Trip(
                startMs = startMs, endMs = endMs, fileName = "trip-$startMs.csv", sampleCount = 1, distanceKm = 1.0,
                maxRpm = null, maxSpeedKmh = null, maxCoolantC = null, trackId = trackId, lapCount = lapCount,
            ),
        )

    @Test
    fun theOldestPlainTripsBeyondTheLimitAreReturnedNewestFirst() = runBlocking {
        (1L..5L).forEach { insert(startMs = it * 1_000) }

        val beyond = trips.getBeyondRetention(keep = 2, limit = 100).map { it.startMs }

        assertEquals(listOf(3_000L, 2_000L, 1_000L), beyond)
    }

    @Test
    fun lapSessionsAndRunningTripsAreNeverReturnedAndDoNotCount() = runBlocking {
        insert(startMs = 1_000)
        insert(startMs = 2_000, trackId = 7, lapCount = 3)
        insert(startMs = 3_000, trackId = 7, lapCount = null)
        insert(startMs = 4_000, endMs = null)
        insert(startMs = 5_000, trackId = 7, lapCount = 0)
        insert(startMs = 6_000)

        val beyond = trips.getBeyondRetention(keep = 1, limit = 100).map { it.startMs }

        // Kept: 6 000 (the newest plain trip). Never returned: the lap sessions (2 000, and 3 000
        // whose lap count is not known yet) and the running trip (4 000). A track with 0 laps is plain.
        assertEquals(listOf(5_000L, 1_000L), beyond)
    }

    @Test
    fun theLimitBatchesTheResult() = runBlocking {
        (1L..10L).forEach { insert(startMs = it * 1_000) }

        assertEquals(listOf(8_000L, 7_000L, 6_000L), trips.getBeyondRetention(keep = 2, limit = 3).map { it.startMs })
    }

    @Test
    fun nothingIsReturnedWithinTheLimit() = runBlocking {
        (1L..3L).forEach { insert(startMs = it * 1_000) }

        assertEquals(emptyList<Trip>(), trips.getBeyondRetention(keep = 3, limit = 100))
    }
}
