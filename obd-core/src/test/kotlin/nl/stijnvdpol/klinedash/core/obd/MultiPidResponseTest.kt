package nl.stijnvdpol.klinedash.core.obd

import nl.stijnvdpol.klinedash.core.elm.ElmResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiPidResponseTest {

    private val lengths = mapOf(0x0C to 2, 0x0D to 1, 0x11 to 1, 0x05 to 1, 0x01 to 4)

    private fun data(vararg lines: String) = ElmResult.Data(lines.toList(), lines.joinToString("\r"))

    @Test
    fun `one line with several blocks is split by the known lengths`() {
        val blocks = MultiPidResponse.split(data("410C1AF80D3C"), lengths)
        assertEquals(setOf(0x0C, 0x0D), blocks.keys)
        assertArrayEquals(intArrayOf(0x1A, 0xF8), blocks.getValue(0x0C))
        assertArrayEquals(intArrayOf(0x3C), blocks.getValue(0x0D))
    }

    @Test
    fun `blocks come out right whatever their order and mix of lengths`() {
        val blocks = MultiPidResponse.split(data("41" + "0132000000" + "0D3C" + "0C1AF8"), lengths)
        assertArrayEquals(intArrayOf(0x32, 0, 0, 0), blocks.getValue(0x01))
        assertArrayEquals(intArrayOf(0x3C), blocks.getValue(0x0D))
        assertArrayEquals(intArrayOf(0x1A, 0xF8), blocks.getValue(0x0C))
    }

    @Test
    fun `multi frame reply is reassembled before splitting`() {
        // 41 0C 1A F8 0D 3C 11 80 = 8 bytes, which the ELM prints as a length line and two frames.
        val blocks = MultiPidResponse.split(data("008", "0:410C1AF80D3C", "1:1180"), lengths)
        assertEquals(setOf(0x0C, 0x0D, 0x11), blocks.keys)
        assertArrayEquals(intArrayOf(0x80), blocks.getValue(0x11))
    }

    @Test
    fun `a message cut short is dropped whole`() {
        assertTrue(MultiPidResponse.split(data("410C1AF80D"), lengths).isEmpty())
    }

    @Test
    fun `a pid that was not requested drops the whole message`() {
        assertTrue(MultiPidResponse.split(data("410C1AF8FF123405" + "40"), lengths).isEmpty())
    }

    @Test
    fun `an ecu sending another length than j1979 cannot shift values into the wrong pid`() {
        // Speed (0D) with two bytes instead of one: read naively, "05" would become coolant = 5 - 40 °C.
        val requested = mapOf(0x0C to 2, 0x0D to 1, 0x05 to 1)
        assertTrue(MultiPidResponse.split(data("410C1AF80D3C05" + "0578"), requested).isEmpty())
    }

    @Test
    fun `a pid that appears twice in one message drops it`() {
        assertTrue(MultiPidResponse.split(data("410D3C0D3C"), lengths).isEmpty())
    }

    @Test
    fun `two ecus each answering their own pids are both used`() {
        val requested = mapOf(0x0C to 2, 0x0D to 1, 0x05 to 1)
        val blocks = MultiPidResponse.split(data("410C1AF8057B", "410D3C"), requested)
        assertEquals(setOf(0x0C, 0x05, 0x0D), blocks.keys)
        assertArrayEquals(intArrayOf(0x3C), blocks.getValue(0x0D))
    }

    @Test
    fun `interleaved multi frame answers of two ecus are not mixed into values`() {
        // Without headers the ELM prints both first frames, then both second frames.
        val blocks = MultiPidResponse.split(data("008", "0:410C1AF80D3C", "0:410C0BB80D10", "1:1180", "1:1120"), lengths)
        assertTrue("got ${blocks.keys}", blocks.isEmpty())
    }

    @Test
    fun `an ecu that answers only the first pid yields only that block`() {
        val blocks = MultiPidResponse.split(data("410C1AF8"), lengths)
        assertEquals(setOf(0x0C), blocks.keys)
    }

    @Test
    fun `lines of other services and stale data are ignored`() {
        val blocks = MultiPidResponse.split(data("4300", "7F0112", "410D3C"), lengths)
        assertEquals(setOf(0x0D), blocks.keys)
    }

    @Test
    fun `the first ecu wins when two answer the same pid`() {
        val blocks = MultiPidResponse.split(data("410D10", "410D20"), lengths)
        assertArrayEquals(intArrayOf(0x10), blocks.getValue(0x0D))
    }

    @Test
    fun `nothing usable gives an empty map`() {
        assertTrue(MultiPidResponse.split(data("41"), lengths).isEmpty())
        assertTrue(MultiPidResponse.split(data("0:"), lengths).isEmpty())
    }

    @Test
    fun `every pid signal of the catalog has the data length its formula needs`() {
        val pidSignals = Signals.all.filter { it.pid != null }
        assertTrue(pidSignals.isNotEmpty())
        for (signal in pidSignals) {
            val length = signal.pidDataLength
            assertNotNull("${signal.id} has no data length", length)
            // The formulas index their bytes: a too short length would throw here.
            assertNotNull(signal.id, signal.decodePayload!!(IntArray(length!!) { 0x80 }))
        }
    }

    @Test
    fun `lengths of the pids that matter for batching are the j1979 ones`() {
        fun length(id: String) = Signals.byId(id)!!.pidDataLength
        assertEquals(2, length("rpm"))
        assertEquals(1, length("speed"))
        assertEquals(1, length("throttle"))
        assertEquals(4, length("mil_on"))
        assertEquals(2, length("o2_b1s1_v"))
    }

    @Test
    fun `adapter commands have no data length`() {
        assertEquals(null, Signals.byId("battery")!!.pidDataLength)
    }
}
