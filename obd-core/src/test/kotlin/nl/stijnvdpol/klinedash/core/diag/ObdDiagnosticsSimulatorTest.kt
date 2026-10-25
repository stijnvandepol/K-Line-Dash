package nl.stijnvdpol.klinedash.core.diag

import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import nl.stijnvdpol.klinedash.core.assertFails
import nl.stijnvdpol.klinedash.core.assertIs
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.elm.ElmConnectionManager
import nl.stijnvdpol.klinedash.core.elm.ProtocolStore
import nl.stijnvdpol.klinedash.core.log.LogKind
import nl.stijnvdpol.klinedash.core.log.RingCommLog
import nl.stijnvdpol.klinedash.core.obd.ObdPollScheduler
import nl.stijnvdpol.klinedash.core.sim.EngineModel
import nl.stijnvdpol.klinedash.core.sim.SimulatorConfig
import nl.stijnvdpol.klinedash.core.sim.SimulatorTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ObdDiagnostics] over a real poller and connection manager talking to the simulator, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class ObdDiagnosticsSimulatorTest {

    private object NoStore : ProtocolStore {
        override suspend fun loadProtocol(adapterKey: String): String? = null
        override suspend fun saveProtocol(adapterKey: String, protocolNumber: String) = Unit
        override suspend fun clearProtocol(adapterKey: String) = Unit
    }

    private class Rig(private val testScope: TestScope, val sim: SimulatorConfig = SimulatorConfig()) {
        val clock: () -> Long = { testScope.testScheduler.currentTime }
        val manager = ElmConnectionManager(
            { SimulatorTransport(sim, EngineModel(sim, Random(7)), clock) }, "sim", NoStore,
            testScope.backgroundScope, log = RingCommLog(clock = clock), random = Random(1),
        )
        val poller = ObdPollScheduler(manager, testScope.backgroundScope, wallClock = clock, monotonicClock = clock)
        val diagnostics = ObdDiagnostics(poller, clock)

        suspend fun connect() {
            manager.start()
            withTimeout(60_000) { manager.state.first { it is ConnectionState.Connected } }
            manager.log.clear()
        }

        /** Everything sent to the adapter since the last [connect], in order. */
        fun sent(): List<String> =
            manager.log.snapshot().filter { it.kind == LogKind.TX }.map { it.text }

        suspend fun elapse(ms: Long) {
            testScope.advanceTimeBy(ms)
            testScope.runCurrent()
        }
    }

    private suspend fun TestScope.connectedRig(configure: SimulatorConfig.() -> Unit = {}): Rig {
        val rig = Rig(this, SimulatorConfig().apply(configure))
        rig.connect()
        return rig
    }

    private fun DiagnosticReport.monitorStatuses(): Map<String, MonitorStatus> =
        monitors.associate { it.id to it.status }

    private fun DiagnosticReport.codes(list: DtcList): List<String> = list.codes.map { it.code }

    @Test
    fun `full read of the default car`() = runTest {
        val rig = connectedRig()

        val report = rig.diagnostics.read()

        assertTrue(report.isMilOn)
        assertEquals(2, report.reportedDtcCount)
        assertTrue(report.isSparkIgnition)
        assertEquals(listOf("P0171", "P1349"), report.codes(report.stored))
        assertEquals(listOf("P0300"), report.codes(report.pending))
        assertTrue(report.stored.isSupported && report.pending.isSupported)
        assertFalse("0A is not supported by this car", report.permanent.isSupported)
        assertEquals(VehicleInfo(null, null), report.vehicleInfo)
        assertEquals(currentTime, report.readAtMs)

        val statuses = report.monitorStatuses()
        assertEquals(MonitorStatus.NOT_READY, statuses["evap"])
        assertEquals(MonitorStatus.READY, statuses["catalyst"])
        assertEquals(MonitorStatus.READY, statuses["misfire"])
        assertEquals(MonitorStatus.NOT_APPLICABLE, statuses["egr"])
    }

    @Test
    fun `freeze frame has the trigger code and the engine values`() = runTest {
        val rig = connectedRig()

        val frame = rig.diagnostics.read().freezeFrame!!

        assertEquals("P0171", frame.triggerCode?.code)
        val values = frame.values.associate { it.signalId to it.value }
        assertEquals(2100.0, values["rpm"]!!, 0.0)
        assertEquals(85.0, values["coolant"]!!, 0.0)
        assertEquals(56.0, values["speed"]!!, 0.0)
        assertEquals(12.5, values["ltft_b1"]!!, 1e-9)
        assertEquals(12.0, values["timing"]!!, 1e-9)
        assertEquals(10, values.size)
    }

    @Test
    fun `vin and ecu name appear when the ecu has them`() = runTest {
        val rig = connectedRig {
            vin = "SB1ZE22E10E123456"
            ecuName = "KLINE-ECU"
        }

        assertEquals(VehicleInfo("SB1ZE22E10E123456", "KLINE-ECU"), rig.diagnostics.read().vehicleInfo)
    }

    @Test
    fun `four stored codes arrive over two frames`() = runTest {
        val rig = connectedRig { storedDtcs.addAll(listOf("P0420", "U1234")) }

        val report = rig.diagnostics.read()

        assertEquals(listOf("P0171", "P1349", "P0420", "U1234"), report.codes(report.stored))
        assertEquals(4, report.reportedDtcCount)
    }

    @Test
    fun `no codes means no freeze frame request and a clean report`() = runTest {
        val rig = connectedRig {
            storedDtcs.clear()
            pendingDtcs.clear()
        }

        val report = rig.diagnostics.read()

        assertFalse(report.isMilOn)
        assertEquals(0, report.reportedDtcCount)
        assertEquals(DtcList(DtcKind.STORED, true, emptyList()), report.stored)
        assertEquals(DtcList(DtcKind.PENDING, true, emptyList()), report.pending)
        assertNull(report.freezeFrame)
        assertTrue(rig.sent().none { it.startsWith("02") })
    }

    @Test
    fun `clearing removes the codes and resets the monitors`() = runTest {
        val rig = connectedRig()

        val outcome = rig.diagnostics.clearCodes()

        val after = assertIs<ClearOutcome.Cleared>(outcome).after
        assertFalse(after.isMilOn)
        assertTrue(after.stored.codes.isEmpty() && after.pending.codes.isEmpty())
        assertNull(after.freezeFrame)
        assertTrue(rig.sim.storedDtcs.isEmpty() && rig.sim.pendingDtcs.isEmpty())
        assertEquals(1, rig.sent().count { it == "04" })

        val ready = after.monitors.filter { it.status == MonitorStatus.READY }
        assertTrue("monitors ready right after a clear: $ready", ready.isEmpty())
        assertEquals(MonitorStatus.NOT_READY, after.monitorStatuses()["catalyst"])
        assertEquals(MonitorStatus.NOT_APPLICABLE, after.monitorStatuses()["egr"])

        val fresh = rig.diagnostics.read()
        assertEquals(after.copy(readAtMs = 0), fresh.copy(readAtMs = 0))
    }

    @Test
    fun `an ecu that refuses is reported and left alone`() = runTest {
        val rig = connectedRig { refusesClear = true }

        val outcome = rig.diagnostics.clearCodes()

        assertEquals(
            ClearOutcome.Refused(
                "The ECU did not clear the codes. Turn the ignition on with the engine off and try again.",
            ),
            outcome,
        )
        assertEquals(listOf("04"), rig.sent())
        assertEquals(listOf("P0171", "P1349"), rig.sim.storedDtcs)
    }

    @Test
    fun `polling is paused while diagnostics own the bus and resumes after`() = runTest {
        val rig = connectedRig()
        rig.poller.start()
        rig.poller.demand("screen", setOf("rpm", "speed", "coolant", "battery"))
        rig.elapse(3_000)
        assertTrue("poller never ran", rig.sent().any { it.startsWith("010C") })
        val sentBefore = rig.sent().size

        val read = async { rig.diagnostics.read() }
        read.await()

        val during = rig.sent().drop(sentBefore)
        val window = during.subList(during.indexOf("0101"), during.lastIndexOf("090A") + 1)
        assertTrue("only ${window.size} diagnostic commands", window.size >= 15)
        val strangers = window.filterNot(::isDiagnosticCommand)
        assertTrue("polling commands inside the read: $strangers", strangers.isEmpty())

        val sentAfter = rig.sent().size
        rig.elapse(3_000)
        assertTrue("polling did not resume", rig.sent().drop(sentAfter).any { it.startsWith("010C") })
    }

    @Test
    fun `read without a connection fails with a user-presentable message`() = runTest {
        val rig = Rig(this)

        val error = assertFails<IllegalStateException> { rig.diagnostics.read() }

        assertEquals("Not connected to the adapter.", error.message)
    }

    @Test
    fun `clear without a connection fails instead of reporting a refusal`() = runTest {
        val rig = Rig(this)

        assertFails<IllegalStateException> { rig.diagnostics.clearCodes() }
    }

    private fun isDiagnosticCommand(command: String): Boolean =
        command in DIAGNOSTIC_COMMANDS || (command.startsWith("02") && command.length == 6)

    private companion object {
        val DIAGNOSTIC_COMMANDS = setOf("0101", "03", "07", "0A", "0902", "090A")
    }
}
