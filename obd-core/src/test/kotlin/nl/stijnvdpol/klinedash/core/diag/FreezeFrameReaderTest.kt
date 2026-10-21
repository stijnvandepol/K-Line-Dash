package nl.stijnvdpol.klinedash.core.diag

import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.assertFails
import nl.stijnvdpol.klinedash.core.elm.ElmLink
import nl.stijnvdpol.klinedash.core.elm.ElmResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezeFrameReaderTest {

    private val now = 123_456L

    private fun reader(link: ElmLink) = FreezeFrameReader(link, clock = { now })

    private fun FreezeFrame.valueOf(id: String): Double? = values.firstOrNull { it.signalId == id }?.value

    @Test
    fun `reads the trigger code and the pids the ecu lists`() = runTest {
        val link = ScriptedLink(
            responder = answering(
                mapOf(
                    "020200" to data("4202000171"),
                    // Bitmap with PIDs 02, 05 and 0C.
                    "020000" to data("42000048100000"),
                    "020500" to data("4205007D"),
                    "020C00" to data("420C001AF8"),
                ),
            ),
        )

        val frame = reader(link).read()!!

        assertEquals("P0171", frame.triggerCode?.code)
        // The frame number byte is skipped: 0x7D = 125 - 40, 0x1AF8 / 4.
        assertEquals(85.0, frame.valueOf("coolant")!!, 0.0)
        assertEquals(1726.0, frame.valueOf("rpm")!!, 0.0)
        assertEquals(listOf("coolant", "rpm"), frame.values.map { it.signalId }.sorted())
        assertTrue(frame.values.all { it.timestampMs == now })
        assertEquals(listOf("020200", "020000", "020C00", "020500"), link.sent)
    }

    @Test
    fun `one request decodes every signal that shares a pid`() = runTest {
        val link = ScriptedLink(
            responder = answering(
                mapOf(
                    "020200" to data("4202000171"),
                    // Bitmap with PID 14 (third byte, 0x10).
                    "020000" to data("42000000001000"),
                    "021400" to data("4214006490"),
                ),
            ),
        )

        val frame = reader(link).read()!!

        assertEquals(listOf("020200", "020000", "021400"), link.sent)
        assertEquals(0.5, frame.valueOf("o2_b1s1_v")!!, 1e-9)
        assertEquals(12.5, frame.valueOf("o2_b1s1_trim")!!, 1e-9)
    }

    @Test
    fun `trigger code 0000 means there is no freeze frame`() = runTest {
        val link = ScriptedLink(responder = answering(mapOf("020200" to data("4202000000"))))

        assertNull(reader(link).read())
        assertEquals(listOf("020200"), link.sent)
    }

    @Test
    fun `no data for the trigger code means there is no freeze frame`() = runTest {
        val link = ScriptedLink(responder = answering(emptyMap()))

        assertNull(reader(link).read())
        assertEquals(listOf("020200"), link.sent)
    }

    @Test
    fun `a trigger code that stays unreadable is retried once and then gives no freeze frame`() = runTest {
        val link = ScriptedLink(responder = { ElmResult.Timeout("") })

        assertNull(reader(link).read())
        assertEquals(listOf("020200", "020200"), link.sent)
    }

    @Test
    fun `falls back to the usual pids when the bitmap request fails`() = runTest {
        val link = ScriptedLink(
            responder = answering(
                mapOf(
                    "020200" to data("4202000171"),
                    "020C00" to data("420C001AF8"),
                    "020D00" to data("420D0038"),
                ),
            ),
        )

        val frame = reader(link).read()!!

        assertEquals(setOf("rpm", "speed"), frame.values.map { it.signalId }.toSet())
        val asked = link.sent.drop(2)
        assertEquals(
            setOf("020C00", "020D00", "020500", "020400", "020600", "020700", "020E00", "020F00", "021000", "021100"),
            asked.toSet(),
        )
    }

    @Test
    fun `pids outside the signal catalog are not requested`() = runTest {
        // The bitmap lists PIDs 02 and 1D; 1D is not a catalog signal, so nothing else is requested.
        val link = ScriptedLink(
            responder = answering(
                mapOf(
                    "020200" to data("4202000171"),
                    "020000" to data("42000040000008"),
                ),
            ),
        )

        val frame = reader(link).read()!!

        assertTrue(frame.values.isEmpty())
        assertEquals(listOf("020200", "020000"), link.sent)
    }

    @Test
    fun `a reply for another frame number is ignored`() = runTest {
        val link = ScriptedLink(
            responder = answering(
                mapOf(
                    "020200" to data("4202010171"),
                ),
            ),
        )

        assertNull(reader(link).read())
    }

    @Test
    fun `link loss propagates`() = runTest {
        val link = ScriptedLink(responder = { throw IllegalStateException("not connected") })

        assertFails<IllegalStateException> { reader(link).read() }
    }
}
