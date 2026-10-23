package nl.stijnvdpol.klinedash.core.diag

import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.assertFails
import nl.stijnvdpol.klinedash.core.elm.ElmResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleInfoReaderTest {

    private val vin = "SB1ZE22E10E123456"

    /** The K-line layout of the simulator: counter byte, then 4 data bytes per line; VIN behind 3 zeros. */
    private fun kLineLines(pid: Int, bytes: List<Int>): Array<String> =
        bytes.chunked(4).mapIndexed { i, chunk ->
            "49%02X%02X".format(pid, i + 1) + chunk.joinToString("") { "%02X".format(it) }.padEnd(8, '0')
        }.toTypedArray()

    private fun ascii(text: String): List<Int> = text.map { it.code }

    private fun vinLines(text: String = vin) = kLineLines(0x02, listOf(0, 0, 0) + ascii(text))

    private fun nameLines(text: String) = kLineLines(0x0A, ascii(text.padEnd(20, '\u0000')))

    // ---- parser ----

    @Test
    fun `k-line vin comes from five lines`() {
        assertEquals(vin, VehicleInfoParser.parseVin(data(*vinLines())))
    }

    @Test
    fun `can vin comes from one multi-frame message`() {
        // 49 02 01 + 17 characters = 20 bytes (0x14): 6 in the first frame, 7 in each following one.
        val result = data("014", "0:490201534231", "1:5A4532324531" + "30", "2:45313233343536")
        assertEquals(vin, VehicleInfoParser.parseVin(result))
    }

    @Test
    fun `vin of the first ecu wins when a second ecu answers too`() {
        val result = data(*vinLines(), *vinLines())
        assertEquals(vin, VehicleInfoParser.parseVin(result))
    }

    @Test
    fun `vin with a forbidden letter is rejected`() {
        for (bad in listOf("SB1ZE22E10E12345O", "SB1ZE22E10E12345I", "SB1ZE22E10E12345Q")) {
            assertNull(bad, VehicleInfoParser.parseVin(data(*vinLines(bad))))
        }
    }

    @Test
    fun `vin of the wrong length or alphabet is rejected`() {
        assertNull(VehicleInfoParser.parseVin(data(*vinLines("SB1ZE22E10E12345"))))
        assertNull(VehicleInfoParser.parseVin(data(*vinLines("sb1ze22e10e123456"))))
        assertNull(VehicleInfoParser.parseVin(data(*vinLines("SB1ZE22E10E12345-"))))
    }

    @Test
    fun `lines for another pid or service are not part of the vin`() {
        assertNull(VehicleInfoParser.parseVin(data("410C1AF8", *nameLines("KLINE-ECU"))))
    }

    @Test
    fun `ecu name is trimmed and its padding dropped`() {
        assertEquals("KLINE-ECU", VehicleInfoParser.parseEcuName(data(*nameLines("KLINE-ECU"))))
        assertEquals("ECU", VehicleInfoParser.parseEcuName(data(*kLineLines(0x0A, ascii("  ECU ")))))
    }

    @Test
    fun `empty ecu name is null`() {
        assertNull(VehicleInfoParser.parseEcuName(data(*kLineLines(0x0A, List(20) { 0 }))))
    }

    // ---- reader ----

    @Test
    fun `reader returns both fields`() = runTest {
        val link = ScriptedLink(
            responder = answering(
                mapOf("0902" to data(*vinLines()), "090A" to data(*nameLines("KLINE-ECU"))),
            ),
        )

        assertEquals(VehicleInfo(vin, "KLINE-ECU"), VehicleInfoReader(link).read())
        assertEquals(listOf("0902", "090A"), link.sent)
    }

    @Test
    fun `no data and question marks give null fields`() = runTest {
        val link = ScriptedLink(
            responder = { if (it == "0902") ElmResult.NoData("NO DATA") else ElmResult.Unknown("?") },
        )

        assertEquals(VehicleInfo(null, null), VehicleInfoReader(link).read())
    }

    @Test
    fun `an invalid vin does not hide the ecu name`() = runTest {
        val link = ScriptedLink(
            responder = answering(
                mapOf("0902" to data(*vinLines("SB1ZE22E10E12345O")), "090A" to data(*nameLines("KLINE-ECU"))),
            ),
        )

        assertEquals(VehicleInfo(null, "KLINE-ECU"), VehicleInfoReader(link).read())
    }

    @Test
    fun `a lost answer is retried once then gives null`() = runTest {
        val link = ScriptedLink(responder = { ElmResult.Timeout("") })

        assertEquals(VehicleInfo(null, null), VehicleInfoReader(link).read())
        assertEquals(listOf("0902", "0902", "090A", "090A"), link.sent)
    }

    @Test
    fun `reading only the vin sends one request and finds the vin`() = runTest {
        val link = ScriptedLink(responder = answering(mapOf("0902" to data(*vinLines()))))

        assertEquals(VinReading.Found(vin), VehicleInfoReader(link).readVin())
        assertEquals(listOf("0902"), link.sent)
    }

    @Test
    fun `no data, a question mark and an invalid vin all mean the car has no vin`() = runTest {
        assertEquals(VinReading.Absent, VehicleInfoReader(ScriptedLink(responder = { ElmResult.NoData("NO DATA") })).readVin())
        assertEquals(VinReading.Absent, VehicleInfoReader(ScriptedLink(responder = { ElmResult.Unknown("?") })).readVin())
        val invalid = ScriptedLink(responder = answering(mapOf("0902" to data(*vinLines("SB1ZE22E10E12345O")))))
        assertEquals(VinReading.Absent, VehicleInfoReader(invalid).readVin())
    }

    @Test
    fun `a lost answer is retried once and then is not an answer`() = runTest {
        val link = ScriptedLink(responder = { ElmResult.Timeout("") })

        assertEquals(VinReading.Unanswered, VehicleInfoReader(link).readVin())
        assertEquals(listOf("0902", "0902"), link.sent)
    }

    @Test
    fun `a bus error or a can error is not an answer either`() = runTest {
        assertEquals(VinReading.Unanswered, VehicleInfoReader(ScriptedLink(responder = { ElmResult.BusError("BUS ERROR") })).readVin())
        assertEquals(VinReading.Unanswered, VehicleInfoReader(ScriptedLink(responder = { ElmResult.CanError("CAN ERROR") })).readVin())
    }

    @Test
    fun `link loss propagates`() = runTest {
        val link = ScriptedLink(responder = { throw IllegalStateException("not connected") })

        assertFails<IllegalStateException> { VehicleInfoReader(link).read() }
    }
}
