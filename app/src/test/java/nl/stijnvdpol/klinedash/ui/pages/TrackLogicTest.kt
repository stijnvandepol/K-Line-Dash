package nl.stijnvdpol.klinedash.ui.pages

import nl.stijnvdpol.klinedash.core.obd.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PeakHoldTest {
    private fun rpm(value: Double, atMs: Long) = Reading("rpm", value, atMs)
    private fun speed(value: Double, atMs: Long) = Reading("speed", value, atMs)

    @Test
    fun `no peaks before the first reading`() {
        assertEquals(Peaks(null, null), PeakHold().withReadings(null, null).peaks)
    }

    @Test
    fun `keeps the highest rpm and speed seen`() {
        val hold = PeakHold()
            .withReadings(rpm(3_000.0, 10), speed(50.0, 10))
            .withReadings(rpm(7_900.0, 20), speed(120.0, 20))
            .withReadings(rpm(2_000.0, 30), speed(30.0, 30))
        assertEquals(Peaks(7_900.0, 120.0), hold.peaks)
    }

    @Test
    fun `rpm and speed are tracked on their own timestamps`() {
        val hold = PeakHold()
            .withReadings(rpm(5_000.0, 10), speed(80.0, 10))
            .withReadings(rpm(5_000.0, 10), speed(90.0, 20))
        assertEquals(Peaks(5_000.0, 90.0), hold.peaks)
        assertEquals(20L, hold.speedSeenMs)
    }

    @Test
    fun `a reading that was counted already changes nothing`() {
        val hold = PeakHold().withReadings(rpm(6_000.0, 10), null)
        assertSame(hold, hold.withReadings(rpm(6_000.0, 10), null))
    }

    @Test
    fun `reset clears the peaks and the old reading cannot bring them back`() {
        val old = rpm(8_100.0, 10)
        val hold = PeakHold().withReadings(old, speed(150.0, 10)).reset(old, speed(150.0, 10))
        assertEquals(Peaks(), hold.peaks)
        assertEquals(Peaks(), hold.withReadings(old, speed(150.0, 10)).peaks)
    }

    @Test
    fun `after a reset a new reading starts the peak again, even a lower one`() {
        val old = rpm(8_100.0, 10)
        val hold = PeakHold().withReadings(old, null).reset(old, null).withReadings(rpm(3_200.0, 20), null)
        assertEquals(3_200.0, hold.peaks.maxRpm!!, 0.0)
        assertNull(hold.peaks.maxSpeed)
    }

    @Test
    fun `reset without readings keeps what was seen so far`() {
        val hold = PeakHold().withReadings(rpm(4_000.0, 50), null).reset(null, null)
        assertEquals(50L, hold.rpmSeenMs)
        assertEquals(Peaks(), hold.peaks)
    }
}

class ShiftLightTest {
    private val shiftRpm = 7_800

    @Test
    fun `flashes from the shift point on`() {
        assertTrue(isShiftLightOn(7_800.0, 7_800.0, isStale = false, shiftRpm = shiftRpm))
        assertTrue(isShiftLightOn(8_200.0, 8_150.0, isStale = false, shiftRpm = shiftRpm))
    }

    @Test
    fun `does not flash below the shift point`() {
        assertFalse(isShiftLightOn(7_799.0, 7_799.0, isStale = false, shiftRpm = shiftRpm))
        assertFalse(isShiftLightOn(3_000.0, 3_000.0, isStale = false, shiftRpm = shiftRpm))
    }

    @Test
    fun `stops at once when the measured rpm drops, while the bar still shows more`() {
        assertFalse(isShiftLightOn(barRpm = 8_000.0, measuredRpm = 7_000.0, isStale = false, shiftRpm = shiftRpm))
    }

    @Test
    fun `waits for the bar to reach the shift point, while the measured rpm is already above it`() {
        assertFalse(isShiftLightOn(barRpm = 7_500.0, measuredRpm = 8_000.0, isStale = false, shiftRpm = shiftRpm))
    }

    @Test
    fun `a stale or missing reading never flashes`() {
        assertFalse(isShiftLightOn(8_000.0, 8_000.0, isStale = true, shiftRpm = shiftRpm))
        assertFalse(isShiftLightOn(8_000.0, null, isStale = false, shiftRpm = shiftRpm))
    }

    @Test
    fun `follows a lowered shift point`() {
        assertTrue(isShiftLightOn(6_500.0, 6_500.0, isStale = false, shiftRpm = 6_500))
        assertFalse(isShiftLightOn(6_400.0, 6_400.0, isStale = false, shiftRpm = 6_500))
    }

    @Test
    fun `the flash is lit for 125 ms and dark for 125 ms, which is 4 Hz`() {
        assertTrue(isFlashLit(0))
        assertTrue(isFlashLit(124))
        assertFalse(isFlashLit(125))
        assertFalse(isFlashLit(249))
        assertTrue(isFlashLit(250))
        val risingEdgesInOneSecond = (1 until 1_000L).count { isFlashLit(it) && !isFlashLit(it - 1) }
        assertEquals(3, risingEdgesInOneSecond)
    }
}
