package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.core.car.CarMake
import nl.stijnvdpol.klinedash.core.car.EngineNotes
import nl.stijnvdpol.klinedash.data.CarSettings

/*
 * What SETUP > Car decides, as plain functions: what the Automatic row says about the car and
 * which engine notes can be switched on. The composables only draw the result.
 */

/** How many VIN characters the page shows: the manufacturer identifier, which names the maker and not the car. */
private const val SHOWN_VIN_CHARACTERS = 3

/** What the Automatic row says about the car. The VIN itself is never shown whole. */
internal sealed interface AutomaticDetection {
    /** No car has reported a VIN yet; the standard meanings are shown. */
    data object NoVin : AutomaticDetection

    /** A VIN was read but its maker is none of the makes; [maker] is the first characters of the VIN. */
    data class OtherMake(val maker: String) : AutomaticDetection

    data class Detected(val make: CarMake, val maker: String) : AutomaticDetection
}

internal fun automaticDetectionOf(settings: CarSettings): AutomaticDetection {
    val vin = settings.detectedVin ?: return AutomaticDetection.NoVin
    val maker = vin.take(SHOWN_VIN_CHARACTERS)
    val make = settings.detectedMake ?: return AutomaticDetection.OtherMake(maker)
    return AutomaticDetection.Detected(make, maker)
}

/** The engines whose notes can be switched on for the car as it stands: the ones of the effective make. */
internal fun engineNotesChoices(settings: CarSettings): List<EngineNotes> =
    EngineNotes.entries.filter { it.make == settings.effectiveMake }
