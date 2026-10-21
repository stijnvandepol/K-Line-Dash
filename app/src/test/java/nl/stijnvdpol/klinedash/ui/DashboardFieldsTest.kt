package nl.stijnvdpol.klinedash.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardFieldsTest {
    private val defaults = listOf("intake_temp", "throttle", "load", "timing", "battery", "maf")

    /** What the target car reports: 01 03 04 05 06 07 0C 0D 0E 0F 10 11 12 13 14 15 1C 20 21. */
    private val targetCarPids = setOf(
        0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x1C, 0x20, 0x21,
    )

    @Test
    fun `shows the default fields while the supported PIDs are unknown`() {
        assertEquals(defaults, dashboardFieldIds(null))
    }

    @Test
    fun `shows the default fields in order when the car supports them all`() {
        assertEquals(defaults, dashboardFieldIds(targetCarPids))
    }

    @Test
    fun `fills an unsupported field from the fallbacks`() {
        val withoutThrottle = targetCarPids - 0x11
        assertEquals(
            listOf("intake_temp", "load", "timing", "battery", "maf", "stft_b1"),
            dashboardFieldIds(withoutThrottle),
        )
    }

    @Test
    fun `uses fallbacks in order and keeps the battery without any PID`() {
        val onlyTrims = setOf(0x06, 0x07, 0x10)
        assertEquals(listOf("battery", "maf", "stft_b1", "ltft_b1"), dashboardFieldIds(onlyTrims))
    }

    @Test
    fun `demand covers the primary fields and the small ones`() {
        assertEquals(defaults.toSet() + "rpm" + "speed" + "coolant", dashboardDemandIds(defaults))
    }

    @Test
    fun `only rpm and speed use their label as the unit`() {
        assertTrue(labelIsUnit("rpm"))
        assertTrue(labelIsUnit("speed"))
        assertFalse(labelIsUnit("coolant"))
    }
}
