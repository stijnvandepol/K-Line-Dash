package nl.stijnvdpol.klinedash.data

import kotlinx.coroutines.flow.Flow

/**
 * GPS and lap-timing options.
 *
 * @property activeTrackId the track whose gates are used for lap timing; null = no lap timing.
 *   Trips started while it is set get that track.
 * @property useSimulatedGps off by default. Only with the simulator transport selected: the position
 *   comes from a simulated circuit driven at the simulator's speed instead of the device's GPS, so
 *   laps can be tested without a car. Ignored with a real adapter. Leave it off to test with the
 *   emulator's own position (`adb emu geo fix <lon> <lat>`).
 */
data class GpsSettings(val activeTrackId: Long? = null, val useSimulatedGps: Boolean = false)

/** Persists [GpsSettings] under "gps.*" keys of the settings table. */
class GpsSettingsRepository(dao: SettingsDao) {
    private val store = TypedSettings(
        dao,
        { rows ->
            val defaults = GpsSettings()
            GpsSettings(
                activeTrackId = rows.parsed(ACTIVE_TRACK, defaults.activeTrackId) { it.toLongOrNull() },
                useSimulatedGps = rows.parsed(SIMULATE, defaults.useSimulatedGps) { it.toBooleanStrictOrNull() },
            )
        },
        { mapOf(ACTIVE_TRACK to it.activeTrackId?.toString(), SIMULATE to it.useSimulatedGps.toString()) },
    )

    val settings: Flow<GpsSettings> = store.flow

    suspend fun update(transform: (GpsSettings) -> GpsSettings) = store.update(transform)

    private companion object {
        const val ACTIVE_TRACK = "gps.activeTrackId"
        const val SIMULATE = "gps.useSimulatedGps"
    }
}
