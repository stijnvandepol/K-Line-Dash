package nl.stijnvdpol.klinedash.data

import kotlinx.coroutines.flow.Flow

/**
 * Trip recording options.
 *
 * @property autoRecord start recording when the engine runs and stop it a grace period after the
 *   engine stopped (or the link is gone); when false only the manual start/stop records.
 */
data class TripSettings(val autoRecord: Boolean = true)

/** Persists [TripSettings] under "trip.*" keys of the settings table. */
class TripSettingsRepository(dao: SettingsDao) {
    private val store = TypedSettings(
        dao,
        { rows ->
            TripSettings(autoRecord = rows.parsed(AUTO_RECORD, TripSettings().autoRecord) { it.toBooleanStrictOrNull() })
        },
        { mapOf(AUTO_RECORD to it.autoRecord.toString()) },
    )

    val settings: Flow<TripSettings> = store.flow

    suspend fun update(transform: (TripSettings) -> TripSettings) = store.update(transform)

    private companion object {
        const val AUTO_RECORD = "trip.autoRecord"
    }
}
