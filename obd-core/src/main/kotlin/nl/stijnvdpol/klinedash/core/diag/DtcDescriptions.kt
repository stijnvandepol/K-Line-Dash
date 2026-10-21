package nl.stijnvdpol.klinedash.core.diag

/**
 * Offline text for a trouble code. [description] is the generic SAE J2012 meaning (or the
 * manufacturer's meaning for P1xxx codes); [vehicleNote] is extra context for the target car
 * (Toyota 2ZZ-GE), null when there is none.
 */
data class DtcDescription(
    val description: String,
    val vehicleNote: String?,
)

/** Looks up offline descriptions; implemented by [BundledDtcDescriptions]. */
interface DtcDescriptions {
    /** Null when the code is not in the database (the UI then shows the code alone). */
    fun describe(dtc: Dtc): DtcDescription?
}
