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
 */
data class TripSettings(val autoRecord: Boolean = true, val autoStartWhenMoving: Boolean = false)

/** Persists [TripSettings] under "trip.*" keys of the settings table. */
class TripSettingsRepository(dao: SettingsDao, decodeDispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private val store = TypedSettings(
        dao,
        { rows ->
            val defaults = TripSettings()
            TripSettings(
                autoRecord = rows.parsed(AUTO_RECORD, defaults.autoRecord) { it.toBooleanStrictOrNull() },
                autoStartWhenMoving = rows.parsed(AUTO_START_MOVING, defaults.autoStartWhenMoving) { it.toBooleanStrictOrNull() },
            )
        },
        { mapOf(AUTO_RECORD to it.autoRecord.toString(), AUTO_START_MOVING to it.autoStartWhenMoving.toString()) },
        decodeDispatcher,
    )

    val settings: Flow<TripSettings> = store.flow

    suspend fun update(transform: (TripSettings) -> TripSettings) = store.update(transform)

    private companion object {
        const val AUTO_RECORD = "trip.autoRecord"
        const val AUTO_START_MOVING = "trip.autoStartWhenMoving"
    }
}
