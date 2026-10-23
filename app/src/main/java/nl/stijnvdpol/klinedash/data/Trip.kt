package nl.stijnvdpol.klinedash.data

import androidx.room.ColumnInfo
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
 *
 * The last columns are a cache of what the CSV says, so the trip list needs no file reads; the CSV
 * stays the source of truth and [nl.stijnvdpol.klinedash.service.TripAnalysisRepository] rebuilds
 * the cache. [trackId] is the track (start/finish gate) the laps are computed for, null for none.
 * [lapCount] and [bestLapMs] are null while no track is set (0 laps is a real answer with a track).
 * [hasGps] is true when at least one row has a position.
 *
 * [lapTimingAfterMs] is where the lap session of [trackId] began, as the time of the last sample
 * before it: laps are only timed from there (plus a lead-in of a few seconds, so the pass at which
 * the line was set counts). Null times the whole trip, as for a track set at the start and for
 * trips recorded before the column existed.
 */
@Entity(tableName = "trips", indices = [Index("startMs"), Index("trackId")])
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
    val trackId: Long? = null,
    @ColumnInfo(defaultValue = "0") val hasGps: Boolean = false,
    val movingTimeMs: Long? = null,
    val estimatedFuelLiters: Double? = null,
    val lapCount: Int? = null,
    val bestLapMs: Long? = null,
    val lapTimingAfterMs: Long? = null,
)

/** The cached part of a [Trip] row, see [TripDao.updateSummary]. */
data class TripSummaryCache(
    val hasGps: Boolean,
    val movingTimeMs: Long?,
    val estimatedFuelLiters: Double?,
    val lapCount: Int?,
    val bestLapMs: Long?,
)

/** Upper bound of the trips recomputed when one track changes. */
const val MAX_TRIPS_PER_TRACK = 1_000

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

    /** Sets the track the laps of a trip are computed for (null removes it) and where its lap session began. */
    @Query("UPDATE trips SET trackId = :trackId, lapTimingAfterMs = :lapTimingAfterMs WHERE id = :id")
    suspend fun setTrack(id: Long, trackId: Long?, lapTimingAfterMs: Long?)

    @Query(
        "UPDATE trips SET hasGps = :hasGps, movingTimeMs = :movingTimeMs, estimatedFuelLiters = :estimatedFuelLiters, " +
            "lapCount = :lapCount, bestLapMs = :bestLapMs WHERE id = :id",
    )
    suspend fun updateSummary(
        id: Long,
        hasGps: Boolean,
        movingTimeMs: Long?,
        estimatedFuelLiters: Double?,
        lapCount: Int?,
        bestLapMs: Long?,
    )

    /** The trips whose laps depend on [trackId], newest first, at most [limit]. */
    @Query("SELECT * FROM trips WHERE trackId = :trackId ORDER BY startMs DESC LIMIT :limit")
    suspend fun getByTrack(trackId: Long, limit: Int): List<Trip>

    /** Forgets [trackId] on every trip that used it (the track is being deleted). */
    @Query("UPDATE trips SET trackId = NULL, lapTimingAfterMs = NULL, lapCount = NULL, bestLapMs = NULL WHERE trackId = :trackId")
    suspend fun clearTrack(trackId: Long)
}

/** Writes the cached statistics of trip [id]. */
suspend fun TripDao.updateSummary(id: Long, cache: TripSummaryCache) =
    updateSummary(id, cache.hasGps, cache.movingTimeMs, cache.estimatedFuelLiters, cache.lapCount, cache.bestLapMs)
