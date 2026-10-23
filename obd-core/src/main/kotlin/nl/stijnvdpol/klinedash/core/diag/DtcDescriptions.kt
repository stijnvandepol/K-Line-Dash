package nl.stijnvdpol.klinedash.core.diag

import nl.stijnvdpol.klinedash.core.car.CarMake

/**
 * Offline text for a trouble code. [description] is the generic SAE J2012 meaning, the make's own
 * meaning for a manufacturer-specific code, or [manufacturerSpecificDescription] when only the
 * manufacturer's tool could say; [vehicleNote] is extra context from the make or engine table,
 * null when there is none.
 */
data class DtcDescription(
    val description: String,
    val vehicleNote: String?,
)

/** What a manufacturer-specific code reads as when no make is chosen (see [manufacturerSpecificDescription]). */
const val MANUFACTURER_SPECIFIC_DESCRIPTION = "Manufacturer-specific code. Its meaning depends on the make."

private const val GENERIC_TOOL = "the dealer's diagnostic tool"

/**
 * What a manufacturer-specific code with no known meaning reads as for a car of [make]: the generic
 * [MANUFACTURER_SPECIFIC_DESCRIPTION] for null, otherwise a sentence naming the make and the tool
 * that reads its full fault memory. Tool names are the brand's own or the one its workshops use.
 */
fun manufacturerSpecificDescription(make: CarMake?): String {
    if (make == null) return MANUFACTURER_SPECIFIC_DESCRIPTION
    val (name, tool) = when (make) {
        CarMake.VW_GROUP -> "VW Group" to "ODIS or VCDS"
        CarMake.BMW_MINI -> "BMW/Mini" to "ISTA or INPA"
        CarMake.MERCEDES_SMART -> "Mercedes/smart" to "Xentry"
        CarMake.OPEL_VAUXHALL -> "Opel/Vauxhall" to GENERIC_TOOL
        CarMake.FORD -> "Ford" to "FORScan or Ford IDS"
        CarMake.RENAULT_DACIA -> "Renault/Dacia" to "Renault CLIP"
        CarMake.PSA -> "Peugeot/Citroen/DS" to "Diagbox"
        CarMake.FIAT_ALFA_LANCIA -> "Fiat/Alfa/Lancia" to "Multiecuscan or Examiner"
        CarMake.VOLVO -> "Volvo" to "VIDA"
        CarMake.TOYOTA_LEXUS -> "Toyota/Lexus" to "Techstream"
        CarMake.HONDA_ACURA -> "Honda/Acura" to "HDS"
        CarMake.NISSAN_INFINITI -> "Nissan/Infiniti" to "Consult"
        CarMake.MAZDA -> "Mazda" to GENERIC_TOOL
        CarMake.MITSUBISHI -> "Mitsubishi" to GENERIC_TOOL
        CarMake.SUBARU -> "Subaru" to GENERIC_TOOL
        CarMake.SUZUKI -> "Suzuki" to GENERIC_TOOL
        CarMake.HYUNDAI_KIA -> "Hyundai/Kia" to GENERIC_TOOL
    }
    return "$name-specific code. Read the full fault memory with $tool."
}

/** Looks up offline descriptions; implemented by [BundledDtcDescriptions]. */
interface DtcDescriptions {
    /**
     * Null when the code is not in the database and is not manufacturer-specific (the UI then
     * shows the code alone).
     */
    fun describe(dtc: Dtc): DtcDescription?
}
