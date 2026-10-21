package nl.stijnvdpol.klinedash.core.diag

/**
 * Reads and clears diagnostic data. Implemented by [ObdDiagnostics] on top of the poller, so live
 * polling pauses while diagnostics own the bus (ObdPoller.withPollingPaused).
 *
 * Both calls throw IllegalStateException / TransportException when the link is not connected or
 * drops mid-read; the caller shows that as "not connected" and lets the user retry.
 */
interface DiagnosticsReader {
    /** Reads MIL, readiness, all code lists, the freeze frame and vehicle info in one pass. */
    suspend fun read(): DiagnosticReport

    /**
     * Sends service 04 (clear codes, freeze frame and readiness). The UI must have confirmed twice
     * and checked that the engine is not running before calling this; it is never retried
     * automatically.
     */
    suspend fun clearCodes(): ClearOutcome
}
