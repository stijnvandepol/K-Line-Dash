package nl.stijnvdpol.klinedash.core.diag

import nl.stijnvdpol.klinedash.core.elm.ElmLink
import nl.stijnvdpol.klinedash.core.elm.ElmResult

/**
 * Reads the code lists of services 03, 07 and 0A over [link]; the caller must hold the bus.
 *
 * What counts as supported:
 *  - A well-formed answer is supported, with or without codes.
 *  - "?" (the adapter does not know the request) is unsupported.
 *  - NO DATA on 03 and 07 is supported-and-empty when PID 01 reported [reportedDtcCount] 0: ECUs of
 *    this era stay silent instead of sending an all-zero frame when there is nothing to report.
 *    With codes reported it is unsupported, because the ECU contradicts itself.
 *  - NO DATA on 0A is always unsupported: permanent codes are mandatory only from about 2010, so
 *    silence on an older car means "not implemented", not "none".
 *  - Anything else (timeout, bus error, garbage, a negative response) is unsupported for 0A, and
 *    an [IllegalStateException] for 03 and 07: reporting "no codes" for an unreadable list would
 *    be a lie the user acts on.
 */
internal class CodeListReader(
    private val link: ElmLink,
    private val format: DtcFormat,
    private val reportedDtcCount: Int,
) {

    suspend fun read(kind: DtcKind): DtcList {
        val result = link.executeRead("%02X".format(DtcParser.serviceFor(kind)))
        return when (result) {
            is ElmResult.Data -> DtcParser.parse(result, kind, format)
                ?.let { DtcList(kind, isSupported = true, codes = it) }
                ?: unreadable(kind)
            is ElmResult.NoData -> {
                val isNone = kind != DtcKind.PERMANENT && reportedDtcCount == 0
                DtcList(kind, isSupported = isNone, codes = emptyList())
            }
            is ElmResult.Unknown -> unsupported(kind)
            else -> unreadable(kind)
        }
    }

    private fun unsupported(kind: DtcKind) = DtcList(kind, isSupported = false, codes = emptyList())

    private fun unreadable(kind: DtcKind): DtcList {
        if (kind == DtcKind.PERMANENT) return unsupported(kind)
        throw IllegalStateException(
            "The ECU did not answer the ${kind.name.lowercase()} fault codes request clearly. Try again.",
        )
    }
}
