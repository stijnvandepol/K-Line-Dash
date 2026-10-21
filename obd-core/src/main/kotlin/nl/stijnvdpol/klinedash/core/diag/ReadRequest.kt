package nl.stijnvdpol.klinedash.core.diag

import nl.stijnvdpol.klinedash.core.elm.ElmLink
import nl.stijnvdpol.klinedash.core.elm.ElmResult

/** Per-request timeout for diagnostic reads; K-line multi-frame replies take well over a second. */
internal const val READ_TIMEOUT_MS = 3_000L

/**
 * Sends a read-only request and repeats it once when the answer was lost or mangled (timeout, bus
 * error, garbage). Reads are idempotent, so this is safe; definitive answers such as NO DATA or
 * "?" are returned at once. Never use this for service 04.
 */
internal suspend fun ElmLink.executeRead(command: String): ElmResult {
    val first = execute(command, READ_TIMEOUT_MS)
    return if (first.isLostOrMangled()) execute(command, READ_TIMEOUT_MS) else first
}

private fun ElmResult.isLostOrMangled(): Boolean = when (this) {
    is ElmResult.Timeout, is ElmResult.BusError, is ElmResult.Garbage,
    is ElmResult.Stopped, is ElmResult.BufferFull -> true
    else -> false
}
