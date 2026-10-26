package nl.stijnvdpol.klinedash.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/**
 * Trip recording options.
 *
 * @property autoRecord start recording when the engine runs and stop it a grace period after the
 *   engine stopped (or the link is gone); when false only the manual start/stop records.
 * @property autoStartWhenMoving also start when the GPS speed stays above 10 km/h for 5 s, for a
 *   phone that is not connected to an adapter (needs [autoRecord] too). Off by default: with an
 *   adapter the engine rule is the better signal.
 * @property keepTrips how many finished trips that are not lap sessions are kept; when a trip ends
 *   the oldest ones beyond that are deleted with their files. One of [KEEP_TRIPS_CHOICES].
 */
data class TripSettings(
    val autoRecord: Boolean = true,
    val autoStartWhenMoving: Boolean = false,
    val keepTrips: Int = DEFAULT_KEEP_TRIPS,
)

/** The most trips the user can choose to keep. */
const val MAX_KEEP_TRIPS = 500

/**
 * The default keeps what the trip list showed before there was a limit (500), so an update deletes
 * nothing the user could see; whoever wants the storage back chooses fewer.
 */
const val DEFAULT_KEEP_TRIPS = MAX_KEEP_TRIPS

/** What the user can choose for [TripSettings.keepTrips]; anything else read from storage is ignored. */
val KEEP_TRIPS_CHOICES = listOf(50, 100, 250, MAX_KEEP_TRIPS)

/** Persists [TripSettings] under "trip.*" keys of the settings table. */
class TripSettingsRepository(dao: SettingsDao, decodeDispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private val store = TypedSettings(
        dao,
        { rows ->
            val defaults = TripSettings()
            TripSettings(
                autoRecord = rows.parsed(AUTO_RECORD, defaults.autoRecord) { it.toBooleanStrictOrNull() },
                autoStartWhenMoving = rows.parsed(AUTO_START_MOVING, defaults.autoStartWhenMoving) { it.toBooleanStrictOrNull() },
                // A stray value must not turn into "delete all but 3 trips": only the offered limits count.
                keepTrips = rows.parsed(KEEP_TRIPS, defaults.keepTrips) { it.toIntOrNull()?.takeIf { keep -> keep in KEEP_TRIPS_CHOICES } },
            )
        },
        {
            mapOf(
                AUTO_RECORD to it.autoRecord.toString(),
                AUTO_START_MOVING to it.autoStartWhenMoving.toString(),
                KEEP_TRIPS to it.keepTrips.toString(),
            )
        },
        decodeDispatcher,
    )

    val settings: Flow<TripSettings> = store.flow

    suspend fun update(transform: (TripSettings) -> TripSettings) = store.update(transform)

    private companion object {
        const val AUTO_RECORD = "trip.autoRecord"
        const val AUTO_START_MOVING = "trip.autoStartWhenMoving"
        const val KEEP_TRIPS = "trip.keepTrips"
    }
}
