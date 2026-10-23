package nl.stijnvdpol.klinedash.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/**
 * Which engine-specific trouble code data applies. [GENERIC] shows only the standard (SAE) meanings,
 * because a manufacturer code such as P1349 means something different per make.
 */
enum class EngineProfile { GENERIC, TOYOTA_2ZZ_GE }

/**
 * Facts about the user's car that the app cannot read from it.
 *
 * @property redlineRpm where the car's own rev counter turns red; sets the scale and the caution and
 *   danger zones of every tachometer. Kept within [MIN_REDLINE_RPM]..[MAX_REDLINE_RPM].
 * @property engineProfile the engine-specific trouble code meanings and notes to show.
 */
data class CarSettings(
    val redlineRpm: Int = DEFAULT_REDLINE_RPM,
    val engineProfile: EngineProfile = EngineProfile.GENERIC,
)

const val DEFAULT_REDLINE_RPM = 6_500
const val MIN_REDLINE_RPM = 3_000
const val MAX_REDLINE_RPM = 10_000
const val REDLINE_STEP_RPM = 250

/** The 2ZZ-GE's tachometer turns red at 8 000; the limiter cuts in about 8 200. */
const val TOYOTA_2ZZ_GE_REDLINE_RPM = 8_000

fun clampRedline(rpm: Int): Int = rpm.coerceIn(MIN_REDLINE_RPM, MAX_REDLINE_RPM)

/**
 * The tachometer scale for a redline: [maxRpm] is the full scale, a whole 1 000 at least 1 000 above
 * the redline so the red zone is always visible; caution starts 1 000 below the redline.
 */
data class RpmScale(val maxRpm: Int, val cautionRpm: Int, val redlineRpm: Int)

private const val RPM_SCALE_STEP = 1_000
private const val RED_ZONE_MIN_RPM = 1_000
private const val CAUTION_SPAN_RPM = 1_000

fun rpmScale(redlineRpm: Int): RpmScale {
    val redline = clampRedline(redlineRpm)
    val maxRpm = ((redline + RED_ZONE_MIN_RPM + RPM_SCALE_STEP - 1) / RPM_SCALE_STEP) * RPM_SCALE_STEP
    return RpmScale(maxRpm = maxRpm, cautionRpm = redline - CAUTION_SPAN_RPM, redlineRpm = redline)
}

/** Persists [CarSettings] under "car.*" keys of the settings table. */
class CarSettingsRepository(dao: SettingsDao, decodeDispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private val store = TypedSettings(
        dao,
        { rows ->
            val defaults = CarSettings()
            CarSettings(
                redlineRpm = rows.parsed(REDLINE, defaults.redlineRpm) { it.toIntOrNull()?.let(::clampRedline) },
                engineProfile = rows.parsed(ENGINE_PROFILE, defaults.engineProfile) { name ->
                    EngineProfile.entries.firstOrNull { it.name == name }
                },
            )
        },
        { mapOf(REDLINE to it.redlineRpm.toString(), ENGINE_PROFILE to it.engineProfile.name) },
        decodeDispatcher,
    )

    val settings: Flow<CarSettings> = store.flow

    suspend fun update(transform: (CarSettings) -> CarSettings) = store.update { clamped(transform(it)) }

    private fun clamped(settings: CarSettings) = settings.copy(redlineRpm = clampRedline(settings.redlineRpm))

    private companion object {
        const val REDLINE = "car.redlineRpm"
        const val ENGINE_PROFILE = "car.engineProfile"
    }
}
