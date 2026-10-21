package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.core.diag.DiagnosticReport
import nl.stijnvdpol.klinedash.core.diag.Dtc
import nl.stijnvdpol.klinedash.core.diag.DtcDescriptions
import nl.stijnvdpol.klinedash.core.diag.DtcList
import nl.stijnvdpol.klinedash.core.diag.FreezeFrame
import nl.stijnvdpol.klinedash.core.diag.MonitorStatus
import nl.stijnvdpol.klinedash.core.diag.ReadinessMonitor
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.obd.Signals
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Everything the diagnostics page decides, as plain functions: which words and rows to show, which
 * pane is visible and whether clearing codes may start. The composables only draw the result.
 */

/**
 * A reading older than this says nothing about the engine now. In engine-off mode the poller asks
 * for rpm every 5 s and a K-line request takes about 0.5 s, so 10 s always covers one fresh answer.
 */
private const val RPM_FRESH_MS = 10_000L
private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

/**
 * Service 04 needs the engine off. Clearing is blocked unless a fresh rpm reading says 0: a missing
 * or stale reading blocks too (deny by default), because then nothing confirms the engine is off.
 */
internal fun isClearBlockedByEngine(rpm: Reading?, nowMs: Long): Boolean =
    rpm == null || nowMs - rpm.timestampMs >= RPM_FRESH_MS || rpm.value > 0.0

/** Wall-clock time of the last read, "14:32:05", in the head unit's time zone. */
internal fun formatClockTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    ClockFormat.format(Instant.ofEpochMilli(epochMs).atZone(zone))

/** What the main area of the page shows. */
internal enum class DiagnosticsPane { NOT_CONNECTED, READING, CLEARING, FAILED, REPORT }

internal enum class DiagnosticsBusy { READING, CLEARING }

internal fun diagnosticsPane(isConnected: Boolean, busy: DiagnosticsBusy?, hasReport: Boolean, readFailed: Boolean): DiagnosticsPane = when {
    busy == DiagnosticsBusy.CLEARING -> DiagnosticsPane.CLEARING
    busy == DiagnosticsBusy.READING -> DiagnosticsPane.READING
    !isConnected -> DiagnosticsPane.NOT_CONNECTED
    hasReport -> DiagnosticsPane.REPORT
    readFailed -> DiagnosticsPane.FAILED
    // Connected, nothing read yet: the automatic first read starts a moment after the page opens.
    else -> DiagnosticsPane.READING
}

/** Read is possible whenever there is a link and nothing else owns the bus. */
internal fun canRead(isConnected: Boolean, busy: DiagnosticsBusy?): Boolean = isConnected && busy == null

/** Clearing needs a report on screen: the user must have seen what is about to be erased. */
internal fun canClear(isConnected: Boolean, busy: DiagnosticsBusy?, hasReport: Boolean): Boolean =
    canRead(isConnected, busy) && hasReport

/** One trouble code with its offline text; both texts are null for a code that is not in the database. */
internal data class CodeRow(val code: Dtc, val description: String?, val note: String?)

internal sealed interface CodeListState {
    /** The ECU does not implement this service. Different from [Empty]: it says nothing about the codes. */
    data object Unsupported : CodeListState

    data object Empty : CodeListState

    data class Codes(val rows: List<CodeRow>) : CodeListState
}

internal fun codeListState(list: DtcList, descriptions: DtcDescriptions): CodeListState = when {
    !list.isSupported -> CodeListState.Unsupported
    list.codes.isEmpty() -> CodeListState.Empty
    else -> CodeListState.Codes(
        list.codes.map { dtc ->
            val text = descriptions.describe(dtc)
            CodeRow(dtc, text?.description, text?.vehicleNote)
        },
    )
}

/** The three code lists of a report with their descriptions resolved. */
internal data class CodeLists(val stored: CodeListState, val pending: CodeListState, val permanent: CodeListState)

internal fun codeLists(report: DiagnosticReport, descriptions: DtcDescriptions) = CodeLists(
    stored = codeListState(report.stored, descriptions),
    pending = codeListState(report.pending, descriptions),
    permanent = codeListState(report.permanent, descriptions),
)

/** The code whose freeze frame the page starts with: the trigger code if it is stored, else the first stored code. */
internal fun defaultSelection(report: DiagnosticReport): Dtc? {
    val stored = report.stored.codes
    val trigger = report.freezeFrame?.triggerCode
    return if (trigger != null && trigger in stored) trigger else stored.firstOrNull()
}

private val MonitorLabels = mapOf(
    "misfire" to "Misfire",
    "fuel_system" to "Fuel system",
    "components" to "Components",
    "catalyst" to "Catalyst",
    "heated_catalyst" to "Heated catalyst",
    "evap" to "Evaporative system",
    "secondary_air" to "Secondary air",
    "ac_refrigerant" to "A/C refrigerant",
    "o2_sensor" to "Oxygen sensor",
    "o2_heater" to "Oxygen sensor heater",
    "egr" to "EGR system",
    "nmhc_catalyst" to "NMHC catalyst",
    "nox_scr" to "NOx / SCR",
    "boost_pressure" to "Boost pressure",
    "exhaust_gas_sensor" to "Exhaust gas sensor",
    "pm_filter" to "Particulate filter",
    "egr_vvt" to "EGR / VVT",
)

/** Readable name of a monitor key; an unknown key is shown as plain words rather than hidden. */
internal fun monitorLabel(id: String): String =
    MonitorLabels[id] ?: id.replace('_', ' ').replaceFirstChar { it.uppercase() }

/** How many of the monitors that apply to this car are ready; "not applicable" ones do not count. */
internal data class ReadinessSummary(val ready: Int, val applicable: Int)

internal fun readinessSummary(monitors: List<ReadinessMonitor>): ReadinessSummary {
    val applicable = monitors.filter { it.status != MonitorStatus.NOT_APPLICABLE }
    return ReadinessSummary(ready = applicable.count { it.status == MonitorStatus.READY }, applicable = applicable.size)
}

/** One line of the freeze frame, already formatted with the decimals and unit of its Mode 01 signal. */
internal data class FreezeRow(val name: String, val value: String, val unit: String)

internal sealed interface FreezeFrameView {
    data object None : FreezeFrameView

    /** The ECU keeps one frame; the selected code is a different one. */
    data class OtherCode(val selected: Dtc, val triggerCode: Dtc) : FreezeFrameView

    data class Values(val triggerCode: Dtc?, val rows: List<FreezeRow>) : FreezeFrameView
}

internal fun freezeFrameView(frame: FreezeFrame?, selected: Dtc?, words: OnOffWords): FreezeFrameView {
    if (frame == null) return FreezeFrameView.None
    val trigger = frame.triggerCode
    if (trigger != null && selected != null && selected != trigger) return FreezeFrameView.OtherCode(selected, trigger)
    val rows = frame.values.mapNotNull { reading ->
        // A value without a signal definition cannot be formatted or named; leave it out.
        val signal = Signals.byId(reading.signalId) ?: return@mapNotNull null
        FreezeRow(signal.shortName, formatReading(signal, reading.value, words), signal.unit)
    }
    return if (rows.isEmpty()) FreezeFrameView.None else FreezeFrameView.Values(trigger, rows)
}

/** Whole seconds a read or clear has been running, for the progress text. */
internal fun elapsedSeconds(startMs: Long, nowMs: Long): Int = ((nowMs - startMs).coerceAtLeast(0L) / 1_000L).toInt()
