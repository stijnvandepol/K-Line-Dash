package nl.stijnvdpol.klinedash.service

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LoopGuardTest {
    @Test
    fun anUnexpectedExceptionEndsTheRoundNotTheLoop() {
        var rounds = 0
        repeat(3) {
            survivingErrors("test", "a round") {
                rounds++
                if (it == 1) throw IllegalStateException("bug")
            }
        }
        assertEquals(3, rounds)
    }

    @Test
    fun cancellationStillEndsTheLoop() {
        try {
            survivingErrors("test", "a round") { throw CancellationException("stop") }
            fail("the cancellation was swallowed")
        } catch (expected: CancellationException) {
            assertEquals("stop", expected.message)
        }
    }
}
