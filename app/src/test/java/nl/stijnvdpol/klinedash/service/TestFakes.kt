package nl.stijnvdpol.klinedash.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import nl.stijnvdpol.klinedash.core.elm.ElmLink
import nl.stijnvdpol.klinedash.core.obd.ObdPoller
import nl.stijnvdpol.klinedash.core.obd.PollStats
import nl.stijnvdpol.klinedash.core.obd.Reading
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
}
