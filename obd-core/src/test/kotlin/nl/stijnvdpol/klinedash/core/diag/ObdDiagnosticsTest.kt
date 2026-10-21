package nl.stijnvdpol.klinedash.core.diag

import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.assertFails
import nl.stijnvdpol.klinedash.core.assertIs
import nl.stijnvdpol.klinedash.core.elm.ElmResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ObdDiagnostics] against a scripted ECU; the end-to-end run is in [ObdDiagnosticsSimulatorTest]. */
class ObdDiagnosticsTest {

    private val refusedReason =
        "The ECU did not clear the codes. Turn the ignition on with the engine off and try again."

    /** A quiet ECU without codes; [overrides] replace single answers. */
    private fun quietEcu(overrides: Map<String, ElmResult> = emptyMap()): (String) -> ElmResult {
        val base = mapOf(
            "0101" to data("410100076565"),
            "03" to data("43000000000000"),
            "07" to data("47000000000000"),
        )
        return answering(base + overrides)
    }

    private fun diagnostics(link: ScriptedLink) = ObdDiagnostics(PassThroughPoller(link), clock = { 42L })

    @Test
    fun `read assembles status, lists and vehicle info`() = runTest {
        val link = ScriptedLink(responder = quietEcu())

        val report = diagnostics(link).read()

        assertFalse(report.isMilOn)
        assertEquals(0, report.reportedDtcCount)
        assertTrue(report.isSparkIgnition)
        assertEquals(DtcList(DtcKind.STORED, true, emptyList()), report.stored)
        assertEquals(DtcList(DtcKind.PENDING, true, emptyList()), report.pending)
        assertEquals(DtcList(DtcKind.PERMANENT, false, emptyList()), report.permanent)
        assertNull(report.freezeFrame)
        assertEquals(VehicleInfo(null, null), report.vehicleInfo)
        assertEquals(42L, report.readAtMs)
        assertEquals(listOf("0101", "03", "07", "0A", "0902", "090A"), link.sent)
    }

    @Test
    fun `a freeze frame is read when pid 01 counted codes even if the list is empty`() = runTest {
        val link = ScriptedLink(
            responder = quietEcu(mapOf("0101" to data("410181076565"), "03" to noData)),
        )

        diagnostics(link).read()

        assertTrue("020200" in link.sent)
    }

    @Test
    fun `the can layout is chosen from the protocol number`() = runTest {
        val link = ScriptedLink(
            protocolNumber = "6",
            responder = quietEcu(mapOf("0101" to data("410181076565"), "03" to data("43010171000000"))),
        )

        val report = diagnostics(link).read()

        assertEquals(listOf("P0171"), report.stored.codes.map { it.code })
    }

    @Test
    fun `an ecu that does not report its status fails the read`() = runTest {
        val link = ScriptedLink(responder = answering(emptyMap()))

        val error = assertFails<IllegalStateException> { diagnostics(link).read() }

        assertTrue(error.message!!.contains("try again"))
        assertEquals(listOf("0101"), link.sent)
    }

    @Test
    fun `link loss propagates from read`() = runTest {
        val link = ScriptedLink(responder = { throw IllegalStateException("not connected") })

        assertFails<IllegalStateException> { diagnostics(link).read() }
    }

    // ---- clearCodes ----

    @Test
    fun `positive response clears and returns a fresh read`() = runTest {
        val link = ScriptedLink(responder = quietEcu(mapOf("04" to data("44"))))

        val outcome = diagnostics(link).clearCodes()

        val cleared = assertIs<ClearOutcome.Cleared>(outcome)
        assertEquals(DtcList(DtcKind.STORED, true, emptyList()), cleared.after.stored)
        assertEquals(1, link.sent.count { it == "04" })
        assertEquals("04", link.sent.first())
    }

    @Test
    fun `no data negative response and silence are refused and 04 is sent once`() = runTest {
        val answers = listOf<ElmResult>(noData, data("7F0422"), ElmResult.Timeout(""), ElmResult.BusError("BUS ERROR"))
        for (answer in answers) {
            val link = ScriptedLink(responder = { answer })

            val outcome = diagnostics(link).clearCodes()

            assertEquals(answer.toString(), ClearOutcome.Refused(refusedReason), outcome)
            assertEquals(answer.toString(), listOf("04"), link.sent)
        }
    }

    @Test
    fun `link loss propagates from clear`() = runTest {
        val link = ScriptedLink(responder = { throw IllegalStateException("not connected") })

        assertFails<IllegalStateException> { diagnostics(link).clearCodes() }
    }
}
