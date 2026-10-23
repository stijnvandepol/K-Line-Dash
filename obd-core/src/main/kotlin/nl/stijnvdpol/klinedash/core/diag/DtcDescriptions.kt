package nl.stijnvdpol.klinedash.core.diag

/**
 * Offline text for a trouble code. [description] is the generic SAE J2012 meaning, the vehicle
 * profile's own meaning for a manufacturer-specific code, or [MANUFACTURER_SPECIFIC_DESCRIPTION]
 * when only the make could say; [vehicleNote] is extra context from the vehicle profile (for now
 * Toyota 2ZZ-GE), null when there is none.
 */
data class DtcDescription(
    val description: String,
    val vehicleNote: String?,
)

/** What a manufacturer-specific code with no known meaning for the chosen car reads as. */
const val MANUFACTURER_SPECIFIC_DESCRIPTION = "Manufacturer-specific code. Its meaning depends on the make."

/** Looks up offline descriptions; implemented by [BundledDtcDescriptions]. */
interface DtcDescriptions {
    /**
     * Null when the code is not in the database and is not manufacturer-specific (the UI then
     * shows the code alone).
     */
    fun describe(dtc: Dtc): DtcDescription?
}
