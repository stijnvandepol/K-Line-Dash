package nl.stijnvdpol.klinedash.core.elm

import nl.stijnvdpol.klinedash.core.diag.DtcFormat
import nl.stijnvdpol.klinedash.core.diag.DtcParser

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

    /**
     * Gave up (e.g. permissions missing, adapter not paired). Requires user action. [reason] is an
     * English sentence meant for the user; [kind] lets the UI treat the cases differently.
     */
    data class Failed(val reason: String, val kind: FailureKind = FailureKind.OTHER) : ConnectionState
}

/** Why a connection ended in [ConnectionState.Failed]. */
enum class FailureKind {
    /** Missing permission, bad configuration and the like. */
    OTHER,

    /**
     * The adapter connects but cannot be used by this app (typically an app-locked dongle), so
     * automatic retries are pointless. The UI should offer the list of compatible adapters.
     */
    UNSUPPORTED_ADAPTER,
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
    /** What the init probes revealed about the adapter itself; [AdapterProfile.UNKNOWN] when nothing is known. */
    val profile: AdapterProfile = AdapterProfile.UNKNOWN,
) {
    /** True when the car talks CAN (ISO 15765, ELM protocols 6-9), in which multi-PID requests are possible. */
    val isCan: Boolean get() = isCanProtocol(protocolNumber)
}

/** True for ELM protocol numbers 6-9 (also in the "A6" auto-detected spelling): ISO 15765-4 CAN. */
internal fun isCanProtocol(protocolNumber: String): Boolean =
    ElmParser.parseProtocolNumber(protocolNumber)?.let(DtcParser::formatFor) == DtcFormat.CAN

/** Persists the detected protocol per adapter, so the next start can skip ATSP0 searching. */
interface ProtocolStore {
    /** [adapterKey] identifies the adapter, e.g. BT address or "wifi:192.168.0.10:35000". */
    suspend fun loadProtocol(adapterKey: String): String?
    suspend fun saveProtocol(adapterKey: String, protocolNumber: String)
    suspend fun clearProtocol(adapterKey: String)
}
