package nl.stijnvdpol.klinedash.core.diag

import nl.stijnvdpol.klinedash.core.elm.ElmLink
import nl.stijnvdpol.klinedash.core.elm.ElmResult

/** What a request for the VIN came to; see [VehicleInfoReader.readVin]. */
sealed interface VinReading {
    /** The car reported a valid VIN. */
    data class Found(val vin: String) : VinReading

    /** The car answered, but has no VIN to give: NO DATA, "?" or a VIN that is not valid. Asking again will not help. */
    data object Absent : VinReading

    /** The answer was lost (timeout, bus error): nothing is known, asking again later may work. */
    data object Unanswered : VinReading
}

/**
 * Reads the VIN (0902) and ECU name (090A) over [link]; the caller must hold the bus. Both are
 * requested directly, without the 0900 support bitmap: on a car that has no service 09 (this one,
 * most likely) that costs two NO DATA answers instead of one, but a car that supports it costs two
 * requests instead of three, and some ECUs answer 0902 while mishandling 0900.
 *
 * A field is null when the ECU does not answer, answers with anything but data, or the VIN is
 * invalid. Link-loss exceptions from [ElmLink.execute] propagate.
 */
class VehicleInfoReader(private val link: ElmLink) {

    suspend fun read(): VehicleInfo = VehicleInfo(
        vin = (readVin() as? VinReading.Found)?.vin,
        ecuName = (link.executeRead(REQUEST_ECU_NAME) as? ElmResult.Data)?.let(VehicleInfoParser::parseEcuName),
    )

    /** Only the VIN (one request, a second if the first answer was lost), for the check right after connecting. */
    suspend fun readVin(): VinReading = when (val result = link.executeRead(REQUEST_VIN)) {
        is ElmResult.Data -> VehicleInfoParser.parseVin(result)?.let(VinReading::Found) ?: VinReading.Absent
        is ElmResult.NoData, is ElmResult.Unknown -> VinReading.Absent
        else -> VinReading.Unanswered
    }

    private companion object {
        const val REQUEST_VIN = "0902"
        const val REQUEST_ECU_NAME = "090A"
    }
}
