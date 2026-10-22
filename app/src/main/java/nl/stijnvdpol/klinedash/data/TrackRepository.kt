package nl.stijnvdpol.klinedash.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The user's circuits and which one is active. Pure persistence: validating the position of a new
 * gate and recomputing the laps of recorded trips is LapSessions' job.
 */
class TrackRepository(private val dao: TrackDao, private val tripDao: TripDao, private val settings: GpsSettingsRepository) {

    /** Newest first. */
    val tracks: Flow<List<Track>> = dao.observeAll()

    /** The track lap timing runs for, or null; re-emits when its gates are edited. */
    val activeTrack: Flow<Track?> = combine(dao.observeAll(), settings.settings) { all, gps ->
        gps.activeTrackId?.let { id -> all.firstOrNull { it.id == id } }
    }.distinctUntilChanged()

    suspend fun get(id: Long): Track? = dao.get(id)

    /** Stores [track] and returns its id. */
    suspend fun add(track: Track): Long = dao.insert(track)

    suspend fun rename(id: Long, name: String) {
        require(name.isNotBlank()) { "A track needs a name" }
        dao.rename(id, name.trim())
    }

    suspend fun setSectors(id: Long, sectors: List<GateSpec>) = dao.setSectors(id, encodeSectors(sectors))

    /** Makes [id] the track for lap timing; null switches lap timing off. */
    suspend fun setActive(id: Long?) = settings.update { it.copy(activeTrackId = id) }

    /** Switches lap timing off when [id] is still the active track; another active track is left alone. */
    suspend fun release(id: Long) = settings.update { if (it.activeTrackId == id) it.copy(activeTrackId = null) else it }

    /** Removes the track; trips that used it keep their data but lose their laps. */
    suspend fun delete(id: Long) {
        release(id)
        tripDao.clearTrack(id)
        dao.deleteById(id)
    }
}
