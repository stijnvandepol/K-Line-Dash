package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.data.UnitSystem
import nl.stijnvdpol.klinedash.core.diag.Dtc
import nl.stijnvdpol.klinedash.core.diag.DtcDescription
import nl.stijnvdpol.klinedash.core.diag.DtcDescriptions
import nl.stijnvdpol.klinedash.core.diag.DtcKind
import nl.stijnvdpol.klinedash.core.diag.DtcList
import nl.stijnvdpol.klinedash.core.diag.FreezeFrame
import nl.stijnvdpol.klinedash.core.diag.MonitorStatus
import nl.stijnvdpol.klinedash.core.diag.ReadinessMonitor
import nl.stijnvdpol.klinedash.core.obd.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class DiagnosticsPresentationTest {
    private val words = OnOffWords(on = "On", off = "Off")
    private val lean = Dtc("P0171")
    private val vvt = Dtc("P1349")

    private val descriptions = object : DtcDescriptions {
        override fun describe(dtc: Dtc): DtcDescription? =
            if (dtc == lean) DtcDescription("System Too Lean (Bank 1)", "Check for vacuum leaks") else null
    }

    private fun monitor(id: String, status: MonitorStatus) = ReadinessMonitor(id, status)

    // --- the clear gate -----------------------------------------------------------------------

    @Test
    fun `a fresh rpm above zero blocks clearing`() {
        assertTrue(isClearBlockedByEngine(Reading("rpm", 850.0, timestampMs = 10_000), nowMs = 12_000))
        assertTrue(isClearBlockedByEngine(Reading("rpm", 1.0, timestampMs = 10_000), nowMs = 12_000))
    }

    @Test
    fun `a fresh rpm of zero allows clearing`() {
        assertFalse(isClearBlockedByEngine(Reading("rpm", 0.0, timestampMs = 10_000), nowMs = 19_999))
    }

    @Test
    fun `an rpm of zero that is 10 seconds or older blocks clearing`() {
        assertTrue(isClearBlockedByEngine(Reading("rpm", 0.0, timestampMs = 10_000), nowMs = 20_000))
    }

    @Test
    fun `no rpm reading blocks clearing`() {
        assertTrue(isClearBlockedByEngine(null, nowMs = 10_000))
    }

    @Test
    fun `clearing needs a link, an idle bus and a report on screen`() {
        assertTrue(canClear(isConnected = true, busy = null, hasReport = true))
        assertFalse(canClear(isConnected = false, busy = null, hasReport = true))
        assertFalse(canClear(isConnected = true, busy = DiagnosticsBusy.READING, hasReport = true))
        assertFalse(canClear(isConnected = true, busy = DiagnosticsBusy.CLEARING, hasReport = true))
        assertFalse(canClear(isConnected = true, busy = null, hasReport = false))
    }

    @Test
    fun `reading needs a link and an idle bus`() {
        assertTrue(canRead(isConnected = true, busy = null))
        assertFalse(canRead(isConnected = false, busy = null))
        assertFalse(canRead(isConnected = true, busy = DiagnosticsBusy.READING))
    }

    // --- which pane is visible ----------------------------------------------------------------

    @Test
    fun `a running read or clear takes over the page`() {
        assertEquals(DiagnosticsPane.READING, diagnosticsPane(true, DiagnosticsBusy.READING, hasReport = true, readFailed = false))
        assertEquals(DiagnosticsPane.CLEARING, diagnosticsPane(true, DiagnosticsBusy.CLEARING, hasReport = true, readFailed = false))
    }

    @Test
    fun `without a link and without a report the page asks to connect`() {
        assertEquals(DiagnosticsPane.NOT_CONNECTED, diagnosticsPane(false, null, hasReport = false, readFailed = false))
    }

    @Test
    fun `a failed read offers to read again`() {
        assertEquals(DiagnosticsPane.FAILED, diagnosticsPane(true, null, hasReport = false, readFailed = true))
    }

    @Test
    fun `a report is only shown while the link is up`() {
        assertEquals(DiagnosticsPane.REPORT, diagnosticsPane(true, null, hasReport = true, readFailed = false))
        assertEquals(DiagnosticsPane.NOT_CONNECTED, diagnosticsPane(false, null, hasReport = true, readFailed = false))
    }

    @Test
    fun `connected with nothing read yet shows the read about to start`() {
        assertEquals(DiagnosticsPane.READING, diagnosticsPane(true, null, hasReport = false, readFailed = false))
    }

    // --- code lists ---------------------------------------------------------------------------

    @Test
    fun `an unsupported list says so, which is not the same as no codes`() {
        assertEquals(CodeListState.Unsupported, codeListState(DtcList(DtcKind.PERMANENT, isSupported = false, codes = emptyList()), descriptions))
        assertEquals(CodeListState.Empty, codeListState(DtcList(DtcKind.STORED, isSupported = true, codes = emptyList()), descriptions))
    }

    @Test
    fun `codes get their description and vehicle note, unknown codes only the code`() {
        val state = codeListState(DtcList(DtcKind.STORED, true, listOf(lean, vvt)), descriptions)
        assertEquals(
            CodeListState.Codes(
                listOf(
                    CodeRow(lean, "System Too Lean (Bank 1)", "Check for vacuum leaks"),
                    CodeRow(vvt, null, null),
                ),
            ),
            state,
        )
    }

    @Test
    fun `selection starts on the freeze frame code when it is stored`() {
        val report = sampleReport(stored = listOf(lean, vvt), freezeFrame = FreezeFrame(vvt, emptyList()))
        assertEquals(vvt, defaultSelection(report))
    }

    @Test
    fun `selection falls back to the first stored code, or none`() {
        assertEquals(lean, defaultSelection(sampleReport(stored = listOf(lean, vvt), freezeFrame = null)))
        assertEquals(lean, defaultSelection(sampleReport(stored = listOf(lean), freezeFrame = FreezeFrame(vvt, emptyList()))))
        assertNull(defaultSelection(sampleReport(stored = emptyList(), freezeFrame = null)))
    }

    // --- readiness ----------------------------------------------------------------------------

    @Test
    fun `summary counts ready monitors among those that apply`() {
        val monitors = listOf(
            monitor("misfire", MonitorStatus.READY),
            monitor("catalyst", MonitorStatus.READY),
            monitor("evap", MonitorStatus.NOT_READY),
            monitor("egr", MonitorStatus.NOT_APPLICABLE),
        )
        assertEquals(ReadinessSummary(ready = 2, applicable = 3), readinessSummary(monitors))
    }

    @Test
    fun `summary of no monitors is zero of zero`() {
        assertEquals(ReadinessSummary(0, 0), readinessSummary(emptyList()))
        assertEquals(ReadinessSummary(0, 0), readinessSummary(listOf(monitor("egr", MonitorStatus.NOT_APPLICABLE))))
    }

    @Test
    fun `monitor keys get readable names, unknown keys become plain words`() {
        assertEquals("Evaporative system", monitorLabel("evap"))
        assertEquals("Oxygen sensor heater", monitorLabel("o2_heater"))
        assertEquals("Some new monitor", monitorLabel("some_new_monitor"))
    }

    // --- freeze frame -------------------------------------------------------------------------

    private val frame = FreezeFrame(
        triggerCode = lean,
        values = listOf(Reading("rpm", 2100.0, 0), Reading("coolant", 85.0, 0), Reading("timing", 12.04, 0), Reading("unknown_signal", 1.0, 0)),
    )

    @Test
    fun `freeze frame values use the signal name, decimals and unit`() {
        val view = freezeFrameView(frame, selected = lean, words = words, UnitSystem.METRIC) as FreezeFrameView.Values
        assertEquals(lean, view.triggerCode)
        assertEquals(
            listOf(FreezeRow("RPM", "2100", "rpm"), FreezeRow("Coolant", "85", "°C"), FreezeRow("Timing", "12.0", "°")),
            view.rows,
        )
    }

    @Test
    fun `a frame for another code is not shown as the selected codes frame`() {
        assertEquals(FreezeFrameView.OtherCode(selected = vvt, triggerCode = lean), freezeFrameView(frame, selected = vvt, words = words, UnitSystem.METRIC))
    }

    @Test
    fun `no frame, or one without usable values, shows none`() {
        assertEquals(FreezeFrameView.None, freezeFrameView(null, lean, words, UnitSystem.METRIC))
        assertEquals(FreezeFrameView.None, freezeFrameView(FreezeFrame(lean, emptyList()), lean, words, UnitSystem.METRIC))
        assertEquals(FreezeFrameView.None, freezeFrameView(FreezeFrame(lean, listOf(Reading("unknown_signal", 1.0, 0))), lean, words, UnitSystem.METRIC))
    }

    @Test
    fun `a frame without a trigger code is shown whatever is selected`() {
        val withoutTrigger = FreezeFrame(null, listOf(Reading("rpm", 900.0, 0)))
        assertTrue(freezeFrameView(withoutTrigger, selected = vvt, words = words, UnitSystem.METRIC) is FreezeFrameView.Values)
    }

    // --- text ---------------------------------------------------------------------------------

    @Test
    fun `read time is a 24 hour clock`() {
        assertEquals("14:05:09", formatClockTime(14 * 3_600_000L + 5 * 60_000L + 9_000L, ZoneOffset.UTC))
        assertEquals("00:00:00", formatClockTime(0, ZoneOffset.UTC))
    }

    @Test
    fun `elapsed seconds are whole seconds and never negative`() {
        assertEquals(7, elapsedSeconds(startMs = 1_000, nowMs = 8_999))
        assertEquals(0, elapsedSeconds(startMs = 5_000, nowMs = 4_000))
    }
}
