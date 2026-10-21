package nl.stijnvdpol.klinedash.core.diag

import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.elm.ElmLink
import nl.stijnvdpol.klinedash.core.elm.ElmResult
import nl.stijnvdpol.klinedash.core.obd.ObdPoller
import nl.stijnvdpol.klinedash.core.obd.ObdReply

/**
 * [DiagnosticsReader] on top of the poller: every call runs inside [ObdPoller.withPollingPaused],
 * so live polling cannot interleave with the diagnostic requests.
 *
 * A read takes a good ten seconds on the K-line car (about 0.5 s per request, ~20 requests with a
 * freeze frame). Reads retry a lost answer once (see [executeRead]); clearing never retries.
 *
 * Failure model: link loss propagates as thrown by [ElmLink.execute] (IllegalStateException,
 * TransportException). A status request the ECU does not answer, or a code list that cannot be
 * read cleanly, throws IllegalStateException with a message fit for the user. That can also
 * happen on the re-read after a successful clear; the codes are then cleared nevertheless and a
 * new [read] shows it.
 */
class ObdDiagnostics(
    private val poller: ObdPoller,
    private val clock: () -> Long = System::currentTimeMillis,
) : DiagnosticsReader {

    override suspend fun read(): DiagnosticReport = poller.withPollingPaused { link -> readReport(link) }

    override suspend fun clearCodes(): ClearOutcome = poller.withPollingPaused { link ->
        if (acknowledgesClear(link.execute(REQUEST_CLEAR, CLEAR_TIMEOUT_MS))) {
            ClearOutcome.Cleared(readReport(link))
        } else {
            ClearOutcome.Refused(REFUSED_REASON)
        }
    }

    private suspend fun readReport(link: ElmLink): DiagnosticReport {
        val connected = link.state.value as? ConnectionState.Connected
            ?: throw IllegalStateException("Not connected to the adapter.")
        val status = readStatus(link)
        val lists = CodeListReader(link, DtcParser.formatFor(connected.info.protocolNumber), status.dtcCount)
        val stored = lists.read(DtcKind.STORED)
        val pending = lists.read(DtcKind.PENDING)
        val permanent = lists.read(DtcKind.PERMANENT)
        // A freeze frame belongs to a stored code; without one, skip the ~10 requests it costs.
        val hasCodes = stored.codes.isNotEmpty() || status.dtcCount > 0
        return DiagnosticReport(
            isMilOn = status.isMilOn,
            reportedDtcCount = status.dtcCount,
            isSparkIgnition = status.isSparkIgnition,
            monitors = status.monitors,
            stored = stored,
            pending = pending,
            permanent = permanent,
            freezeFrame = if (hasCodes) FreezeFrameReader(link, clock = clock).read() else null,
            vehicleInfo = VehicleInfoReader(link).read(),
            readAtMs = clock(),
        )
    }

    private suspend fun readStatus(link: ElmLink): EngineStatus {
        val data = link.executeRead(REQUEST_STATUS) as? ElmResult.Data
        val bytes = data?.let { ObdReply.payload(it, MODE_CURRENT_DATA, PID_STATUS) }
        return bytes?.let(ReadinessDecoder::decode)
            ?: throw IllegalStateException("The ECU did not report its status. Turn the ignition on and try again.")
    }

    /** Positive response to service 04 is "44" (with headers off). */
    private fun acknowledgesClear(result: ElmResult): Boolean =
        result is ElmResult.Data && result.lines.any { it.startsWith(RESPONSE_CLEAR) }

    private companion object {
        const val MODE_CURRENT_DATA = 1
        const val PID_STATUS = 1
        const val REQUEST_STATUS = "0101"
        const val REQUEST_CLEAR = "04"
        const val RESPONSE_CLEAR = "44"

        /** Slightly longer than a read: a slow ECU that did clear must not be reported as refusing. */
        const val CLEAR_TIMEOUT_MS = 5_000L

        const val REFUSED_REASON =
            "The ECU did not clear the codes. Turn the ignition on with the engine off and try again."
    }
}
