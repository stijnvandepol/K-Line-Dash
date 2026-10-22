package nl.stijnvdpol.klinedash.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/** How values are shown and edited: metric (km/h, °C, kPa, L) or imperial (mph, °F, psi, US gallons). Stored values and exports stay metric. */
enum class UnitSystem { METRIC, IMPERIAL }

/**
 * App-wide choices that belong to no single screen.
 *
 * @property unitSystem the units of every value the app shows or lets the user edit.
 * @property setupCompleted true once the first-run setup was finished or skipped; the setup wizard
 *   is only shown on its own while this is false (SETUP > Help can run it again).
 */
data class GeneralSettings(val unitSystem: UnitSystem = UnitSystem.METRIC, val setupCompleted: Boolean = false)

/** Persists [GeneralSettings] under "general.*" keys of the settings table. */
class GeneralSettingsRepository(dao: SettingsDao, decodeDispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private val store = TypedSettings(dao, ::decodeGeneralSettings, ::encodeGeneralSettings, decodeDispatcher)

    val settings: Flow<GeneralSettings> = store.flow

    suspend fun update(transform: (GeneralSettings) -> GeneralSettings) = store.update(transform)
}

internal object GeneralSettingsKeys {
    const val UNITS = "general.units"
    const val SETUP_COMPLETED = "general.setupCompleted"
}

internal fun decodeGeneralSettings(rows: Map<String, String>): GeneralSettings {
    val d = GeneralSettings()
    return GeneralSettings(
        unitSystem = rows.parsed(GeneralSettingsKeys.UNITS, d.unitSystem) { name -> UnitSystem.entries.firstOrNull { it.name == name } },
        setupCompleted = rows.parsed(GeneralSettingsKeys.SETUP_COMPLETED, d.setupCompleted) { it.toBooleanStrictOrNull() },
    )
}

internal fun encodeGeneralSettings(s: GeneralSettings): Map<String, String?> = mapOf(
    GeneralSettingsKeys.UNITS to s.unitSystem.name,
    GeneralSettingsKeys.SETUP_COMPLETED to s.setupCompleted.toString(),
)
