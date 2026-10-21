package nl.stijnvdpol.klinedash.core.obd

import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.assertFails
import nl.stijnvdpol.klinedash.core.elm.ElmResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedPidDetectorTest {

    /** Reported by the 2ZZ-GE (docs/research/esp32-obd2-lessons.md), without the bitmap PID 0x20. */
    private val firstBlockOfRealCar = setOf(
        0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F,
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x1C,
    )

    private fun data(vararg lines: String) = ElmResult.Data(lines.toList(), lines.joinToString("\r"))

    /** Answers from [replies] by command and records what was asked; other commands get NO DATA. */
    private class FakeEcu(private val replies: Map<String, ElmResult>) {
        val asked = mutableListOf<String>()

        suspend fun execute(command: String): ElmResult {
            asked += command
            return replies[command] ?: ElmResult.NoData("NO DATA")
        }
    }

    @Test
    fun `parseBitmap maps bits to pids starting at base plus one`() {
        val pids = SupportedPidDetector.parseBitmap(0x00, intArrayOf(0xBE, 0x1F, 0xF8, 0x11))
        assertEquals(firstBlockOfRealCar, pids)
    }

    @Test
    fun `parseBitmap offsets pids by the block base and leaves out the next block pid`() {
        val pids = SupportedPidDetector.parseBitmap(0x20, intArrayOf(0x80, 0x00, 0x00, 0x01))
        assertEquals(setOf(0x21), pids)
    }

    @Test
    fun `parseBitmap of all ones covers every pid of the block except the bitmap pid`() {
        val pids = SupportedPidDetector.parseBitmap(0x40, intArrayOf(0xFF, 0xFF, 0xFF, 0xFF))
        assertEquals((0x41..0x5F).toSet(), pids)
    }

    @Test
    fun `parseBitmap rejects a bitmap of the wrong size`() {
        assertFails<IllegalArgumentException> { SupportedPidDetector.parseBitmap(0, intArrayOf(0xFF)) }
    }

    @Test
    fun `real car chain follows the next block bit and stops when it is clear`() = runTest {
        val ecu = FakeEcu(
            mapOf(
                "0100" to data("4100BE1FF811"),
                "0120" to data("412080000000"),
            ),
        )

        val pids = SupportedPidDetector.detect(ecu::execute)

        assertEquals(firstBlockOfRealCar + 0x21, pids)
        assertEquals(listOf("0100", "0120"), ecu.asked)
    }

    @Test
    fun `first block alone when the next block bit is clear`() = runTest {
        val ecu = FakeEcu(mapOf("0100" to data("4100BE1FF810")))

        val pids = SupportedPidDetector.detect(ecu::execute)

        assertEquals(firstBlockOfRealCar, pids)
        assertEquals(listOf("0100"), ecu.asked)
    }

    @Test
    fun `chain continues through consecutive blocks`() = runTest {
        val ecu = FakeEcu(
            mapOf(
                "0100" to data("410000000001"),
                "0120" to data("412000000001"),
                "0140" to data("414000000001"),
                "0160" to data("416080000000"),
            ),
        )

        val pids = SupportedPidDetector.detect(ecu::execute)

        assertEquals(setOf(0x61), pids)
        assertEquals(listOf("0100", "0120", "0140", "0160"), ecu.asked)
    }

    @Test
    fun `chain never asks beyond the last defined block`() = runTest {
        val replies = (0..0xE0 step 0x20).associate { base ->
            "01%02X".format(base) to data("41%02X00000001".format(base))
        }
        val ecu = FakeEcu(replies)

        SupportedPidDetector.detect(ecu::execute)

        assertEquals(listOf("0100", "0120", "0140", "0160", "0180", "01A0", "01C0", "01E0"), ecu.asked)
    }

    @Test
    fun `two ecus answering one request are or-ed including the next block bit`() = runTest {
        val ecu = FakeEcu(
            mapOf(
                "0100" to data("4100BE1FF810", "410040000001"),
                "0120" to data("412080000000"),
            ),
        )

        val pids = SupportedPidDetector.detect(ecu::execute)

        assertEquals(firstBlockOfRealCar + 0x02 + 0x21, pids)
        assertEquals(listOf("0100", "0120"), ecu.asked)
    }

    @Test
    fun `foreign and truncated lines in a reply are ignored`() = runTest {
        val ecu = FakeEcu(mapOf("0100" to data("410D32", "4100BE1F", "4100BE1FF810")))

        val pids = SupportedPidDetector.detect(ecu::execute)

        assertEquals(firstBlockOfRealCar, pids)
    }

    @Test
    fun `no data on a later block keeps what was found`() = runTest {
        val ecu = FakeEcu(mapOf("0100" to data("4100BE1FF811")))

        val pids = SupportedPidDetector.detect(ecu::execute)

        assertEquals(firstBlockOfRealCar, pids)
        assertEquals(listOf("0100", "0120"), ecu.asked)
    }

    @Test
    fun `timeout or bus error on a later block keeps what was found`() = runTest {
        for (failure in listOf(ElmResult.Timeout(""), ElmResult.BusError("BUS ERROR"))) {
            val ecu = FakeEcu(mapOf("0100" to data("4100BE1FF811"), "0120" to failure))

            assertEquals(firstBlockOfRealCar, SupportedPidDetector.detect(ecu::execute))
        }
    }

    @Test
    fun `failure on the first block throws with the command and the result`() = runTest {
        val failures = listOf<ElmResult>(
            ElmResult.NoData("NO DATA"),
            ElmResult.Timeout("partial"),
            ElmResult.InitError("UNABLE TO CONNECT"),
            data("410D32"),
        )
        for (failure in failures) {
            val ecu = FakeEcu(mapOf("0100" to failure))

            val thrown = assertFails<SupportedPidDetectionException> {
                SupportedPidDetector.detect(ecu::execute)
            }

            val message = thrown.message.orEmpty()
            assertTrue(message, message.contains("0100"))
            assertEquals(listOf("0100"), ecu.asked)
        }
    }
}
