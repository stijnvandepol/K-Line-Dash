package nl.stijnvdpol.klinedash.core.export

import java.io.File

/**
 * Serves trip CSV files to a phone or laptop on the same Wi-Fi network, as a small read-only web
 * page with download links. Implemented by [HttpTripExportServer].
 *
 * Security model (documented deviation from guidelines §6, see CODE GUIDELINES.md "Known
 * oddities"): plain HTTP on the local network only, because an offline head unit cannot present a
 * trusted TLS certificate. Mitigations: off by default and started only by the user, only IPv4
 * peers on a private network ([isLocalNetworkAddress]), a one-time
 * PIN shown on the head unit, a session cookie after the PIN, a lockout after repeated wrong PINs,
 * read-only (GET downloads of listed files only, never a path from the request), requests that do
 * not come from the server's own IP address are refused (login CSRF, DNS rebinding), and an
 * automatic stop after [ExportServerConfig.idleTimeoutMs] without a login or a request with a
 * valid session.
 */
interface TripExportServer {
    /** Starts listening; returns the port actually bound. Idempotent while running. */
    fun start(): Int

    /** Stops listening and closes all connections. Never throws. */
    fun stop()

    val isRunning: Boolean
}

/** Files offered for download, re-read on every page request so new trips appear. */
fun interface TripFileSource {
    fun files(): List<File>
}

/** Settings of one export run; [pin] is the one-time PIN shown on the head unit. */
data class ExportServerConfig(
    /** 0 picks a free port; the app passes a fixed one so the address stays the same. */
    val port: Int = 8080,
    val pin: String,
    val idleTimeoutMs: Long = 10 * 60 * 1000L,
    val maxPinAttempts: Int = 5,
)
