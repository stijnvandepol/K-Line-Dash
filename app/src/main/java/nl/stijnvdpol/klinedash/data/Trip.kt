package nl.stijnvdpol.klinedash.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One recorded trip. The samples live in the CSV file [fileName] in the app's trips folder; this row
 * is the index with the summary. [endMs] is null while the trip is recording, and stays null when
 * the head unit lost power mid-trip until the next app start recovers it (see TripRecovery).
 */
@Entity(tableName = "trips", indices = [Index("startMs")])
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long?,
    val fileName: String,
    val sampleCount: Int,
    val distanceKm: Double,
    val maxRpm: Double?,
    val maxSpeedKmh: Double?,
    val maxCoolantC: Double?,
)

@Dao
interface TripDao {
    /** Returns the generated id. */
    @Insert
    suspend fun insert(trip: Trip): Long

    /** Writes the final summary of a trip. */
    @Query(
        "UPDATE trips SET endMs = :endMs, sampleCount = :sampleCount, distanceKm = :distanceKm, " +
            "maxRpm = :maxRpm, maxSpeedKmh = :maxSpeedKmh, maxCoolantC = :maxCoolantC WHERE id = :id",
    )
    suspend fun finish(
        id: Long,
        endMs: Long,
        sampleCount: Int,
        distanceKm: Double,
        maxRpm: Double?,
        maxSpeedKmh: Double?,
        maxCoolantC: Double?,
    )

    /** Newest first, bounded by [limit] (the list is read on the head unit's small heap). */
    @Query("SELECT * FROM trips ORDER BY startMs DESC LIMIT :limit")
    fun observeNewest(limit: Int): Flow<List<Trip>>

    @Query("SELECT * FROM trips ORDER BY startMs DESC LIMIT :limit")
    suspend fun getNewest(limit: Int): List<Trip>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun get(id: Long): Trip?

    /** Trips that never got an end time: recording right now, or cut off by a power loss. */
    @Query("SELECT * FROM trips WHERE endMs IS NULL")
    suspend fun getUnfinished(): List<Trip>

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteById(id: Long)
}
