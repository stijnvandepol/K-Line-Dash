package nl.stijnvdpol.klinedash.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import nl.stijnvdpol.klinedash.core.elm.ElmLink
import nl.stijnvdpol.klinedash.core.obd.ObdPoller
import nl.stijnvdpol.klinedash.core.obd.PollStats
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.data.SettingEntry
import nl.stijnvdpol.klinedash.data.SettingsDao
import nl.stijnvdpol.klinedash.data.Track
import nl.stijnvdpol.klinedash.data.TrackDao
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.data.TripDao

/** Poller that only keeps the readings it is given and remembers who demanded what. */
class FakePoller : ObdPoller {
    override val readings = MutableStateFlow<Map<String, Reading>>(emptyMap())
    override val supportedPids: StateFlow<Set<Int>?> = MutableStateFlow(null)
    override val stats: StateFlow<PollStats> = MutableStateFlow(PollStats())
    val demands = HashMap<String, Set<String>>()

    override fun demand(owner: String, signalIds: Set<String>) {
        demands[owner] = signalIds
    }

    override fun release(owner: String) {
        demands.remove(owner)
    }

    override fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun <T> withPollingPaused(block: suspend (ElmLink) -> T): T = error("not used")
}

/** In-memory trip table. */
class FakeTripDao : TripDao {
    val rows = MutableStateFlow<List<Trip>>(emptyList())
    private var nextId = 1L

    override suspend fun insert(trip: Trip): Long {
        val id = nextId++
        rows.value = rows.value + trip.copy(id = id)
        return id
    }

    override suspend fun finish(
        id: Long,
        endMs: Long,
        sampleCount: Int,
        distanceKm: Double,
        maxRpm: Double?,
        maxSpeedKmh: Double?,
        maxCoolantC: Double?,
    ) {
        rows.value = rows.value.map {
            if (it.id != id) {
                it
            } else {
                it.copy(
                    endMs = endMs, sampleCount = sampleCount, distanceKm = distanceKm,
                    maxRpm = maxRpm, maxSpeedKmh = maxSpeedKmh, maxCoolantC = maxCoolantC,
                )
            }
        }
    }

    override fun observeNewest(limit: Int): Flow<List<Trip>> =
        rows.map { list -> list.sortedByDescending { it.startMs }.take(limit) }

    override suspend fun getNewest(limit: Int): List<Trip> = rows.value.sortedByDescending { it.startMs }.take(limit)

    override suspend fun get(id: Long): Trip? = rows.value.firstOrNull { it.id == id }

    override suspend fun getUnfinished(): List<Trip> = rows.value.filter { it.endMs == null }

    override suspend fun deleteById(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override suspend fun setTrack(id: Long, trackId: Long?, lapTimingAfterMs: Long?) =
        update(id) { it.copy(trackId = trackId, lapTimingAfterMs = lapTimingAfterMs) }

    override suspend fun updateSummary(
        id: Long,
        hasGps: Boolean,
        movingTimeMs: Long?,
        estimatedFuelLiters: Double?,
        lapCount: Int?,
        bestLapMs: Long?,
    ) = update(id) {
        it.copy(
            hasGps = hasGps, movingTimeMs = movingTimeMs, estimatedFuelLiters = estimatedFuelLiters,
            lapCount = lapCount, bestLapMs = bestLapMs,
        )
    }

    override suspend fun getByTrack(trackId: Long, limit: Int): List<Trip> =
        rows.value.filter { it.trackId == trackId }.sortedByDescending { it.startMs }.take(limit)

    override suspend fun clearTrack(trackId: Long) {
        rows.value = rows.value.map { if (it.trackId == trackId) it.copy(trackId = null, lapTimingAfterMs = null, lapCount = null, bestLapMs = null) else it }
    }

    private fun update(id: Long, change: (Trip) -> Trip) {
        rows.value = rows.value.map { if (it.id == id) change(it) else it }
    }
}

/** In-memory track table. */
class FakeTrackDao : TrackDao {
    val rows = MutableStateFlow<List<Track>>(emptyList())
    private var nextId = 1L

    override suspend fun insert(track: Track): Long {
        val id = nextId++
        rows.value = rows.value + track.copy(id = id)
        return id
    }

    override suspend fun rename(id: Long, name: String) = update(id) { it.copy(name = name) }

    override suspend fun setSectors(id: Long, sectorsJson: String) = update(id) { it.copy(sectorsJson = sectorsJson) }

    override suspend fun get(id: Long): Track? = rows.value.firstOrNull { it.id == id }

    override fun observeAll(): Flow<List<Track>> = rows.map { list -> list.sortedByDescending { it.createdMs } }

    override suspend fun deleteById(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    private fun update(id: Long, change: (Track) -> Track) {
        rows.value = rows.value.map { if (it.id == id) change(it) else it }
    }
}

/** In-memory key/value settings table. */
class FakeSettingsDao : SettingsDao {
    private val rows = MutableStateFlow<Map<String, String>>(emptyMap())

    override fun observeAll(): Flow<List<SettingEntry>> = rows.map { map -> map.map { SettingEntry(it.key, it.value) } }

    override suspend fun getAll(): List<SettingEntry> = rows.value.map { SettingEntry(it.key, it.value) }

    override suspend fun get(key: String): SettingEntry? = rows.value[key]?.let { SettingEntry(key, it) }

    override suspend fun upsert(entry: SettingEntry) {
        rows.value = rows.value + (entry.key to entry.value)
    }

    override suspend fun delete(key: String) {
        rows.value = rows.value - key
    }
}

/** The trip being recorded, moved by hand. */
class FakeLiveLaps : LiveLaps {
    override val live = MutableStateFlow(LiveTripState.Idle)
    var currentLap: List<GeoFix> = emptyList()

    override suspend fun currentLapFixes(): List<GeoFix> = currentLap
}

/** A location source the test moves by hand; a fix is fresh until [isStale] is set. */
class FakeLocationSource(override var usesDeviceGps: Boolean = true) : LocationSource {
    override val fix = MutableStateFlow<GeoFix?>(null)
    override val status = MutableStateFlow<GpsStatus>(GpsStatus.Idle)
    override var isRunning = false
        private set
    var isStale = false
    var canStartNow = true
    var starts = 0

    override fun freshFix(maxAgeMs: Long): GeoFix? = fix.value.takeUnless { isStale }

    override fun canStart() = canStartNow

    override fun start() {
        if (!canStartNow || isRunning) return
        isRunning = true
        starts++
    }

    override fun stop() {
        isRunning = false
    }

    override fun refresh() = Unit
}
