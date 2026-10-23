package nl.stijnvdpol.klinedash.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import nl.stijnvdpol.klinedash.core.car.CarMake
import nl.stijnvdpol.klinedash.core.car.EngineNotes
import nl.stijnvdpol.klinedash.core.car.carMakeFromVin
import nl.stijnvdpol.klinedash.core.diag.VehicleInfoParser

/**
 * What the user chose for the car's make, which decides whose trouble code meanings the DIAG page
 * shows. [Automatic] follows the make that the VIN read from the car points to.
 */
sealed interface MakeChoice {
    data object Automatic : MakeChoice

    /** A car of a make the app has no family for: the standard meanings only. */
    data object Other : MakeChoice

    data class Specific(val make: CarMake) : MakeChoice
}

/** Whose trouble codes apply: the make (null for any other make) and the engine notes that go with it. */
data class CarIdentity(val make: CarMake?, val engineNotes: EngineNotes?)

/**
 * Facts about the user's car: some the app cannot read from it, some it has read.
 *
 * @property redlineRpm where the car's own rev counter turns red; sets the scale and the caution and
 *   danger zones of every tachometer. Kept within [MIN_REDLINE_RPM]..[MAX_REDLINE_RPM].
 * @property makeChoice the user's choice of make; see [effectiveMake] for what it comes to.
 * @property detectedVin the last VIN read from a car, or null when no car has reported one. It stays
 *   on the device. The detected make is derived from it ([detectedMake]), so a better VIN table
 *   improves a car that was read earlier.
 * @property engineNotes the engine the user said the car has; only used while it belongs to [effectiveMake].
 */
data class CarSettings(
    val redlineRpm: Int = DEFAULT_REDLINE_RPM,
    val makeChoice: MakeChoice = MakeChoice.Automatic,
    val detectedVin: String? = null,
    val engineNotes: EngineNotes? = null,
) {
    /** The make the VIN points to, or null when there is no VIN or its maker is not one of [CarMake]. */
    val detectedMake: CarMake? get() = detectedVin?.let(::carMakeFromVin)

    /** A manual choice always wins. Automatic uses the detected make and without one stays generic: a guess would show wrong meanings. */
    val effectiveMake: CarMake?
        get() = when (val choice = makeChoice) {
            MakeChoice.Automatic -> detectedMake
            MakeChoice.Other -> null
            is MakeChoice.Specific -> choice.make
        }

    val effectiveEngineNotes: EngineNotes? get() = engineNotes?.takeIf { it.make == effectiveMake }

    val identity: CarIdentity get() = CarIdentity(effectiveMake, effectiveEngineNotes)

    /**
     * A VIN has been read. The same VIN changes nothing; a different one replaces the detection, and
     * with Automatic the engine notes go too: they described the earlier car and cannot be detected.
     * A manual choice stays whatever the VIN says.
     */
    fun withVin(vin: String): CarSettings {
        require(VehicleInfoParser.isVin(vin)) { "Not a VIN" }
        if (vin == detectedVin) return this
        return copy(detectedVin = vin, engineNotes = notesKeptWhenCarChanges())
    }

    /** The car on the bus reports no VIN, so a detection from an earlier car no longer applies to it. */
    fun withoutVin(): CarSettings =
        if (detectedVin == null) this else copy(detectedVin = null, engineNotes = notesKeptWhenCarChanges())

    private fun notesKeptWhenCarChanges(): EngineNotes? = engineNotes.takeUnless { makeChoice == MakeChoice.Automatic }
}

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

/**
 * Persists [CarSettings] under "car.*" keys of the settings table.
 *
 * Release 1.1.0 stored a trouble code profile under "car.engineProfile": TOYOTA_2ZZ_GE becomes the
 * Toyota / Lexus make with the 2ZZ-GE notes, GENERIC becomes Automatic. That is carried over once,
 * before the settings are first read or changed (see [migrateEngineProfile]).
 */
class CarSettingsRepository(
    private val dao: SettingsDao,
    decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val store = TypedSettings(dao, ::decodeCarSettings, ::encodeCarSettings, decodeDispatcher)

    val settings: Flow<CarSettings> = flow {
        migrateEngineProfile()
        emitAll(store.flow)
    }

    suspend fun update(transform: (CarSettings) -> CarSettings) {
        migrateEngineProfile()
        store.update { clamped(transform(it)) }
    }

    /** A VIN was read from the car (when connecting, or by a diagnostics read). */
    suspend fun recordVin(vin: String) = update { it.withVin(vin) }

    /** The car answered that it has no VIN. */
    suspend fun recordNoVin() = update { it.withoutVin() }

    private fun clamped(settings: CarSettings) = settings.copy(redlineRpm = clampRedline(settings.redlineRpm))

    /**
     * Writes the notes first and the make last, so an interruption leaves rows that still mean the
     * same thing, and the 1.1.0 row goes at the end. A make the user has chosen since wins over the old profile.
     */
    private suspend fun migrateEngineProfile() {
        val legacy = dao.get(LEGACY_ENGINE_PROFILE)?.value ?: return
        if (legacy == LEGACY_TOYOTA_2ZZ_GE && dao.get(MAKE_CHOICE) == null) {
            dao.upsert(SettingEntry(ENGINE_NOTES, EngineNotes.TOYOTA_2ZZ_GE.name))
            dao.upsert(SettingEntry(MAKE_CHOICE, MakeChoice.Specific(CarMake.TOYOTA_LEXUS).storageName()))
        }
        dao.delete(LEGACY_ENGINE_PROFILE)
    }
}

private const val REDLINE = "car.redlineRpm"
private const val MAKE_CHOICE = "car.makeChoice"
private const val DETECTED_VIN = "car.detectedVin"
private const val ENGINE_NOTES = "car.engineNotes"
private const val LEGACY_ENGINE_PROFILE = "car.engineProfile"
private const val LEGACY_TOYOTA_2ZZ_GE = "TOYOTA_2ZZ_GE"
private const val CHOICE_AUTOMATIC = "AUTOMATIC"
private const val CHOICE_OTHER = "OTHER"

private fun MakeChoice.storageName(): String = when (this) {
    MakeChoice.Automatic -> CHOICE_AUTOMATIC
    MakeChoice.Other -> CHOICE_OTHER
    is MakeChoice.Specific -> make.name
}

private fun parseMakeChoice(name: String): MakeChoice? = when (name) {
    CHOICE_AUTOMATIC -> MakeChoice.Automatic
    CHOICE_OTHER -> MakeChoice.Other
    else -> CarMake.entries.firstOrNull { it.name == name }?.let(MakeChoice::Specific)
}

private fun decodeCarSettings(rows: Map<String, String>): CarSettings {
    val defaults = CarSettings()
    return CarSettings(
        redlineRpm = rows.parsed(REDLINE, defaults.redlineRpm) { it.toIntOrNull()?.let(::clampRedline) },
        makeChoice = rows.parsed(MAKE_CHOICE, defaults.makeChoice, ::parseMakeChoice),
        // A stored value that is not a VIN is dropped without a log line, which would print it.
        detectedVin = rows[DETECTED_VIN]?.takeIf(VehicleInfoParser::isVin),
        engineNotes = rows.parsed(ENGINE_NOTES, defaults.engineNotes) { name -> EngineNotes.entries.firstOrNull { it.name == name } },
    )
}

private fun encodeCarSettings(settings: CarSettings): Map<String, String?> = mapOf(
    REDLINE to settings.redlineRpm.toString(),
    MAKE_CHOICE to settings.makeChoice.storageName(),
    DETECTED_VIN to settings.detectedVin,
    ENGINE_NOTES to settings.engineNotes?.name,
)
