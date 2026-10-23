package nl.stijnvdpol.klinedash.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import nl.stijnvdpol.klinedash.core.diag.ClearOutcome
import nl.stijnvdpol.klinedash.core.diag.DiagnosticReport
import nl.stijnvdpol.klinedash.core.diag.DiagnosticsReader
import nl.stijnvdpol.klinedash.core.diag.Dtc
import nl.stijnvdpol.klinedash.core.diag.DtcDescription
import nl.stijnvdpol.klinedash.core.diag.DtcDescriptions
import nl.stijnvdpol.klinedash.core.elm.ElmLink
import nl.stijnvdpol.klinedash.core.obd.ObdPoller
import nl.stijnvdpol.klinedash.core.obd.PollStats
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.data.EngineProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    private val lean = Dtc("P0171")
    private val vvt = Dtc("P1349")
    private var nowMs = 100_000L

    private class FakePoller : ObdPoller {
        override val readings = MutableStateFlow<Map<String, Reading>>(emptyMap())
        override val supportedPids: StateFlow<Set<Int>?> = MutableStateFlow(null)
        override val stats: StateFlow<PollStats> = MutableStateFlow(PollStats())
        override fun demand(owner: String, signalIds: Set<String>) = Unit
        override fun release(owner: String) = Unit
        override fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun <T> withPollingPaused(block: suspend (ElmLink) -> T): T = error("the fake reader never uses the bus")
    }

    private class FakeReader(private val reports: List<DiagnosticReport>) : DiagnosticsReader {
        var readCalls = 0
        var clearCalls = 0
        var readFailure: Exception? = null
        var clearFailure: Exception? = null
        var clearOutcome: (() -> ClearOutcome)? = null
        var readGate: CompletableDeferred<Unit>? = null

        override suspend fun read(): DiagnosticReport {
            readCalls++
            readGate?.await()
            readFailure?.let { throw it }
            return reports[minOf(readCalls, reports.size) - 1]
        }

        override suspend fun clearCodes(): ClearOutcome {
            clearCalls++
            clearFailure?.let { throw it }
            return clearOutcome?.invoke() ?: error("no outcome set")
        }
    }

    private val withCodes = sampleReport(stored = listOf(lean, vvt), pending = listOf(Dtc("P0300")), readAtMs = 1)
    private val afterClear = sampleReport(readAtMs = 2)

    private val poller = FakePoller()
    private val pollerFlow = MutableStateFlow<ObdPoller?>(null)
    private val connected = MutableStateFlow(true)
    private val profile = MutableStateFlow(EngineProfile.GENERIC)
    private lateinit var reader: FakeReader

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        reader = FakeReader(listOf(withCodes, afterClear))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): DiagnosticsViewModel {
        val vm = DiagnosticsViewModel(
            loadDescriptions = ::descriptionsFor,
            engineProfile = profile,
            pollerFlow = pollerFlow,
            connectedFlow = connected,
            createReader = { reader },
            clock = { nowMs },
        )
        pollerFlow.value = poller
        return vm
    }

    /** The texts of a profile differ, so a test can tell which profile a row was resolved with. */
    private fun descriptionsFor(engineProfile: EngineProfile) = object : DtcDescriptions {
        override fun describe(dtc: Dtc) = DtcDescription("${engineProfile.name} text of ${dtc.code}", null)
    }

    private fun DiagnosticsViewModel.storedDescriptions(): List<String?> =
        (state.value.codeLists?.stored as CodeListState.Codes).rows.map { it.description }

    private fun setRpm(value: Double, atMs: Long = nowMs) {
        poller.readings.value = mapOf("rpm" to Reading("rpm", value, atMs))
    }

    private fun DiagnosticsViewModel.goToFinalStep() {
        requestClear()
        continueClear()
    }

    // --- reading ------------------------------------------------------------------------------

    @Test
    fun `opening the page on a link reads once, a second open does not read again`() {
        val vm = viewModel()
        vm.readOnce()
        vm.readOnce()
        assertEquals(1, reader.readCalls)
        assertEquals(withCodes, vm.state.value.report)
        assertEquals(lean, vm.state.value.selected)
        assertNull(vm.state.value.busy)
    }

    @Test
    fun `nothing is read without a connection`() {
        connected.value = false
        val vm = viewModel()
        vm.readOnce()
        assertEquals(0, reader.readCalls)
    }

    @Test
    fun `the read button reads again and replaces the report`() {
        val vm = viewModel()
        vm.readOnce()
        vm.read()
        assertEquals(2, reader.readCalls)
        assertEquals(afterClear, vm.state.value.report)
    }

    @Test
    fun `a read is ignored while another one runs`() {
        val vm = viewModel()
        reader.readGate = CompletableDeferred()
        vm.read()
        vm.read()
        assertEquals(1, reader.readCalls)
        assertEquals(DiagnosticsBusy.READING, vm.state.value.busy)
        reader.readGate?.complete(Unit)
        assertNull(vm.state.value.busy)
    }

    @Test
    fun `a failed read shows the error and is not retried by itself`() {
        reader.readFailure = IllegalStateException("link dropped")
        val vm = viewModel()
        vm.readOnce()
        vm.readOnce()
        val state = vm.state.value
        assertTrue(state.readFailed)
        assertNull(state.report)
        assertNull(state.busy)
        assertEquals(1, reader.readCalls)
    }

    @Test
    fun `descriptions are resolved when the report arrives`() {
        val vm = viewModel()
        vm.read()
        assertEquals(listOf("GENERIC text of P0171", "GENERIC text of P1349"), vm.storedDescriptions())
    }

    @Test
    fun `a report read under another profile is described with that profile`() {
        profile.value = EngineProfile.TOYOTA_2ZZ_GE
        val vm = viewModel()
        vm.read()
        assertEquals(listOf("TOYOTA_2ZZ_GE text of P0171", "TOYOTA_2ZZ_GE text of P1349"), vm.storedDescriptions())
    }

    @Test
    fun `changing the profile re-describes the report on screen without reading again`() {
        val vm = viewModel()
        vm.read()
        profile.value = EngineProfile.TOYOTA_2ZZ_GE
        assertEquals(listOf("TOYOTA_2ZZ_GE text of P0171", "TOYOTA_2ZZ_GE text of P1349"), vm.storedDescriptions())
        assertEquals(withCodes, vm.state.value.report)
        assertEquals(1, reader.readCalls)
    }

    @Test
    fun `changing the profile without a report changes nothing`() {
        val vm = viewModel()
        profile.value = EngineProfile.TOYOTA_2ZZ_GE
        assertNull(vm.state.value.codeLists)
    }

    @Test
    fun `a new link throws the old report away and reads again on the next open`() {
        val vm = viewModel()
        vm.readOnce()
        pollerFlow.value = FakePoller()
        assertNull(vm.state.value.report)
        vm.readOnce()
        assertEquals(2, reader.readCalls)
    }

    @Test
    fun `a dropped link clears the report and the next connection reads again`() {
        val vm = viewModel()
        vm.readOnce()
        connected.value = false
        assertNull(vm.state.value.report)
        assertFalse(vm.state.value.isConnected)
        connected.value = true
        vm.readOnce()
        assertEquals(2, reader.readCalls)
        assertEquals(afterClear, vm.state.value.report)
    }

    @Test
    fun `a link that drops during a read leaves no busy state behind`() {
        val vm = viewModel()
        reader.readGate = CompletableDeferred()
        vm.read()
        connected.value = false
        assertNull(vm.state.value.busy)
        assertEquals(ClearDialog.NONE, vm.state.value.dialog)
    }

    @Test
    fun `selecting a stored code keeps the report`() {
        val vm = viewModel()
        vm.read()
        vm.select(vvt)
        assertEquals(vvt, vm.state.value.selected)
        assertEquals(withCodes, vm.state.value.report)
    }

    // --- clearing -----------------------------------------------------------------------------

    @Test
    fun `clearing is not offered before a report was read`() {
        val vm = viewModel()
        vm.requestClear()
        assertEquals(ClearDialog.NONE, vm.state.value.dialog)
    }

    @Test
    fun `with the engine running clear explains instead of proceeding`() {
        val vm = viewModel()
        vm.read()
        setRpm(850.0)
        vm.requestClear()
        assertEquals(ClearDialog.ENGINE_RUNNING, vm.state.value.dialog)
        vm.continueClear()
        vm.confirmClear()
        assertEquals(0, reader.clearCalls)
    }

    @Test
    fun `the two confirmations lead to exactly one clear request`() {
        reader.clearOutcome = { ClearOutcome.Cleared(afterClear) }
        val vm = viewModel()
        vm.read()
        setRpm(0.0)

        vm.requestClear()
        assertEquals(ClearDialog.INTRO, vm.state.value.dialog)
        assertEquals(0, reader.clearCalls)

        vm.continueClear()
        assertEquals(ClearDialog.FINAL, vm.state.value.dialog)
        assertEquals(0, reader.clearCalls)

        vm.confirmClear()
        assertEquals(1, reader.clearCalls)
        assertEquals(ClearDialog.NONE, vm.state.value.dialog)
        assertEquals(afterClear, vm.state.value.report)
        assertEquals(ClearNotice.Cleared, vm.state.value.notice)
        assertNull(vm.state.value.busy)
    }

    @Test
    fun `the final confirmation cannot be reached by skipping the first one`() {
        reader.clearOutcome = { ClearOutcome.Cleared(afterClear) }
        val vm = viewModel()
        vm.read()
        vm.confirmClear()
        assertEquals(0, reader.clearCalls)
        vm.requestClear()
        vm.confirmClear()
        assertEquals(0, reader.clearCalls)
    }

    @Test
    fun `cancelling at either step sends nothing`() {
        val vm = viewModel()
        vm.read()
        vm.requestClear()
        vm.dismissDialog()
        vm.confirmClear()
        assertEquals(ClearDialog.NONE, vm.state.value.dialog)

        vm.goToFinalStep()
        vm.dismissDialog()
        vm.confirmClear()
        assertEquals(0, reader.clearCalls)
    }

    @Test
    fun `the engine is checked again at the final confirmation`() {
        val vm = viewModel()
        vm.read()
        setRpm(0.0)
        vm.goToFinalStep()
        setRpm(900.0)
        vm.confirmClear()
        assertEquals(0, reader.clearCalls)
        assertEquals(ClearDialog.ENGINE_RUNNING, vm.state.value.dialog)
    }

    @Test
    fun `an old rpm reading of zero does not count as engine off`() {
        val vm = viewModel()
        vm.read()
        setRpm(0.0, atMs = nowMs - 10_000)
        vm.requestClear()
        assertEquals(ClearDialog.ENGINE_RUNNING, vm.state.value.dialog)
        assertEquals(0, reader.clearCalls)
    }

    @Test
    fun `without any rpm reading clearing does not start`() {
        val vm = viewModel()
        vm.read()
        vm.requestClear()
        assertEquals(ClearDialog.ENGINE_RUNNING, vm.state.value.dialog)
        assertEquals(0, reader.clearCalls)
    }

    @Test
    fun `a refusal shows the reason and keeps what was on screen`() {
        reader.clearOutcome = { ClearOutcome.Refused("Turn the engine off and try again.") }
        val vm = viewModel()
        vm.read()
        setRpm(0.0)
        vm.goToFinalStep()
        vm.confirmClear()
        assertEquals(1, reader.clearCalls)
        assertEquals(ClearNotice.Refused("Turn the engine off and try again."), vm.state.value.notice)
        assertEquals(withCodes, vm.state.value.report)
        assertNull(vm.state.value.busy)
    }

    @Test
    fun `a link that drops while clearing is reported and not retried`() {
        reader.clearFailure = IllegalStateException("not connected")
        val vm = viewModel()
        vm.read()
        setRpm(0.0)
        vm.goToFinalStep()
        vm.confirmClear()
        assertEquals(1, reader.clearCalls)
        assertEquals(ClearNotice.LinkLost, vm.state.value.notice)
        assertNull(vm.state.value.busy)
        assertFalse(vm.state.value.dialog != ClearDialog.NONE)
    }

    @Test
    fun `a new read removes the notice of the last clear`() {
        reader.clearOutcome = { ClearOutcome.Cleared(afterClear) }
        val vm = viewModel()
        vm.read()
        setRpm(0.0)
        vm.goToFinalStep()
        vm.confirmClear()
        vm.read()
        assertNull(vm.state.value.notice)
    }
}
