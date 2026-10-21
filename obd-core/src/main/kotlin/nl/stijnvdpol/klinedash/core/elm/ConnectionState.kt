package nl.stijnvdpol.klinedash.core.elm

/** Lifecycle of the adapter link as shown in the UI and used by the service. */
sealed interface ConnectionState {
    data object Idle : ConnectionState

    /** Opening the transport (BT socket, TCP, BLE GATT). */
    data class Connecting(val target: String) : ConnectionState

    /** Transport is up; running the AT init sequence / protocol detection. [step] is shown in the UI. */
    data class Initializing(val step: String) : ConnectionState

    /** Adapter initialised and the ECU answered. */
    data class Connected(val info: AdapterInfo) : ConnectionState

    /** Waiting [delayMs] before reconnect attempt [attempt] after [reason]. */
    data class Reconnecting(val attempt: Int, val delayMs: Long, val reason: String) : ConnectionState

    /** Gave up (e.g. permissions missing, adapter not paired). Requires user action. */
    data class Failed(val reason: String) : ConnectionState
}

data class AdapterInfo(
    /** As reported by ATI, e.g. "ELM327 v1.5". Clones lie; informational only. */
    val elmVersion: String,
    /**
     * What gets saved and replayed next time: an ELM protocol number as used by ATSPx, e.g.
     * "3" (ISO 9141-2), "4"/"5" (KWP slow/fast), or "T" for the Toyota K-line profile
     * ([Elm327Session.TOYOTA_PROFILE]).
     */
    val protocolNumber: String,
    /** As reported by ATDP, e.g. "ISO 9141-2". */
    val protocolName: String,
    /** Battery voltage from ATRV, null if the adapter does not support it. */
    val voltage: Double?,
    /** True if the protocol came from storage and detection was skipped. */
    val usedSavedProtocol: Boolean,
    /** AT commands the adapter answered with "?" during init (e.g. "ATAT1"). */
    val unsupportedCommands: Set<String>,
)

/** Persists the detected protocol per adapter, so the next start can skip ATSP0 searching. */
interface ProtocolStore {
    /** [adapterKey] identifies the adapter, e.g. BT address or "wifi:192.168.0.10:35000". */
    suspend fun loadProtocol(adapterKey: String): String?
    suspend fun saveProtocol(adapterKey: String, protocolNumber: String)
    suspend fun clearProtocol(adapterKey: String)
}
