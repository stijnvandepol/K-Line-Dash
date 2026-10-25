package nl.stijnvdpol.klinedash.core.obd

import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.elm.ElmResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseCountSuffixTest {

    private val data = ElmResult.Data(listOf("410C1AF8"), "410C1AF8")

    /** Answers the requests in order from [replies]; the last one repeats. Records what was sent. */
    private class Script(private val replies: List<ElmResult>) {
        val sent = ArrayList<String>()

        suspend fun transmit(command: String): ElmResult {
            sent += command
            return replies[(sent.size - 1).coerceAtMost(replies.lastIndex)]
        }
    }

    @Test
    fun `data confirms the digit and it stays on`() = runTest {
        val suffix = ResponseCountSuffix()
        val script = Script(listOf(data))

        suffix.send("010C", script::transmit)
        suffix.send("010D", script::transmit)

        assertEquals(listOf("010C1", "010D1"), script.sent)
        assertTrue(suffix.isSettled)
    }

    @Test
    fun `no data confirms the digit too`() = runTest {
        val suffix = ResponseCountSuffix()
        val script = Script(listOf(ElmResult.NoData("NO DATA")))

        suffix.send("010C", script::transmit)

        assertTrue(suffix.isSettled)
        assertEquals(listOf("010C1"), script.sent)
    }

    @Test
    fun `adapter commands never carry the digit`() = runTest {
        val suffix = ResponseCountSuffix()
        val script = Script(listOf(data))

        suffix.send("ATRV", script::transmit)

        assertEquals(listOf("ATRV"), script.sent)
        assertFalse(suffix.isSettled)
    }

    @Test
    fun `a question mark disables the digit at once and the request is repeated without it`() = runTest {
        val suffix = ResponseCountSuffix()
        val script = Script(listOf(ElmResult.Unknown("?"), data))

        val result = suffix.send("010C", script::transmit)
        suffix.send("010D", script::transmit)

        assertEquals(data, result)
        assertEquals(listOf("010C1", "010C", "010D"), script.sent)
        assertTrue(suffix.isSettled)
    }

    @Test
    fun `a timeout disables the digit at once and the request is repeated without it`() = runTest {
        val suffix = ResponseCountSuffix()
        val script = Script(listOf(ElmResult.Timeout(""), data))

        suffix.send("010C", script::transmit)
        suffix.send("010C", script::transmit)

        assertEquals(listOf("010C1", "010C", "010C"), script.sent)
    }

    @Test
    fun `one transient error leaves the digit untested and it is tried again`() = runTest {
        val transients = listOf(
            ElmResult.BusError("DATA ERROR"),
            ElmResult.Garbage("x"),
            ElmResult.CanError("CAN ERROR"),
            ElmResult.InitError("BUS INIT: ...ERROR"),
            ElmResult.Stopped("STOPPED"),
        )
        for (transient in transients) {
            val suffix = ResponseCountSuffix()
            val script = Script(listOf(transient, data))

            val first = suffix.send("010C", script::transmit)
            assertEquals(transient, first)
            assertFalse(transient.toString(), suffix.isSettled)

            suffix.send("010C", script::transmit)
            assertEquals(transient.toString(), listOf("010C1", "010C1"), script.sent)
            assertTrue(suffix.isSettled)
        }
    }

    @Test
    fun `two non-data replies in a row disable the digit and repeat the request`() = runTest {
        val suffix = ResponseCountSuffix()
        val script = Script(listOf(ElmResult.BusError("DATA ERROR"), ElmResult.Garbage("x"), data))

        suffix.send("010C", script::transmit)
        val second = suffix.send("010C", script::transmit)
        suffix.send("010C", script::transmit)

        assertEquals(data, second)
        assertEquals(listOf("010C1", "010C1", "010C", "010C"), script.sent)
    }

    @Test
    fun `a settled digit survives later errors`() = runTest {
        val suffix = ResponseCountSuffix()
        val script = Script(listOf(data, ElmResult.Unknown("?"), ElmResult.Timeout("")))

        suffix.send("010C", script::transmit)
        suffix.send("010C", script::transmit)
        suffix.send("010C", script::transmit)

        assertEquals(listOf("010C1", "010C1", "010C1"), script.sent)
    }
}
