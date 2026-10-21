package nl.stijnvdpol.klinedash.core.diag

import nl.stijnvdpol.klinedash.core.obd.Reading

/** A diagnostic trouble code in its standard 5-character form, e.g. "P0171", "C0035", "U0100". */
data class Dtc(val code: String) {
    init {
        require(DTC_FORMAT.matches(code)) { "Not a DTC: \"$code\"" }
    }

    /** True for manufacturer-specific codes (P1xxx, P3xxx and the other second-digit 1/3 ranges). */
    val isManufacturerSpecific: Boolean get() = code[1] == '1' || code[1] == '3'

    override fun toString(): String = code

    private companion object {
        val DTC_FORMAT = Regex("[PCBU][0-3][0-9A-F]{3}")
    }
}

/** Which OBD service a code list came from. */
enum class DtcKind {
    /** Service 03: confirmed codes that (may) light the MIL. */
    STORED,

    /** Service 07: detected once, not yet confirmed. */
    PENDING,

    /** Service 0A: cannot be cleared with service 04 (mandatory only from ~2010, so often absent). */
    PERMANENT,
}

/** One code list; [isSupported] false when the ECU does not implement that service (NO DATA / "?"). */
data class DtcList(
    val kind: DtcKind,
    val isSupported: Boolean,
    val codes: List<Dtc>,
)

enum class MonitorStatus { READY, NOT_READY, NOT_APPLICABLE }

/** One OBD readiness monitor from service 01 PID 01, e.g. "Catalyst", "Oxygen sensor". */
data class ReadinessMonitor(
    /** Stable key, e.g. "catalyst"; the UI maps it to a label. */
    val id: String,
    val status: MonitorStatus,
)

/**
 * Snapshot the ECU stored when [triggerCode] was set (service 02, frame 00). K-line ECUs of this
 * era keep a single frame. [values] reuse the Mode 01 signal ids and formulas.
 */
data class FreezeFrame(
    /** Service 02 PID 02; null if the ECU does not report it. */
    val triggerCode: Dtc?,
    val values: List<Reading>,
)

/** Service 09 data; each field null when the ECU does not provide it (common before ~2005). */
data class VehicleInfo(
    val vin: String?,
    val ecuName: String?,
)

/** Everything the diagnostics screen shows, read in one pass with polling paused. */
data class DiagnosticReport(
    /** Service 01 PID 01: malfunction indicator lamp ("check engine") on. */
    val isMilOn: Boolean,
    /** Number of stored codes the ECU reports in PID 01 (may differ from the list on some ECUs). */
    val reportedDtcCount: Int,
    /** Spark-ignition engine (true for the 2ZZ-GE); decides which monitor set applies. */
    val isSparkIgnition: Boolean,
    val monitors: List<ReadinessMonitor>,
    val stored: DtcList,
    val pending: DtcList,
    val permanent: DtcList,
    /** Null when the ECU has no freeze frame (no stored code, or service 02 unsupported). */
    val freezeFrame: FreezeFrame?,
    val vehicleInfo: VehicleInfo,
    /** System.currentTimeMillis() when the read finished. */
    val readAtMs: Long,
)

/** Result of a service 04 clear request. */
sealed interface ClearOutcome {
    /** The ECU acknowledged ("44"); [after] is a fresh read to show what is left. */
    data class Cleared(val after: DiagnosticReport) : ClearOutcome

    /**
     * The ECU refused or did not answer. Most ECUs refuse while the engine runs; [reason] is
     * user-presentable and says what to do (ignition on, engine off).
     */
    data class Refused(val reason: String) : ClearOutcome
}
