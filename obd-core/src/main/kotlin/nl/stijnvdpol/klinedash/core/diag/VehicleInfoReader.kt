package nl.stijnvdpol.klinedash.core.diag

import nl.stijnvdpol.klinedash.core.elm.ElmLink
import nl.stijnvdpol.klinedash.core.elm.ElmResult

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
        vin = (link.executeRead(REQUEST_VIN) as? ElmResult.Data)?.let(VehicleInfoParser::parseVin),
        ecuName = (link.executeRead(REQUEST_ECU_NAME) as? ElmResult.Data)?.let(VehicleInfoParser::parseEcuName),
    )

    private companion object {
        const val REQUEST_VIN = "0902"
        const val REQUEST_ECU_NAME = "090A"
    }
}
