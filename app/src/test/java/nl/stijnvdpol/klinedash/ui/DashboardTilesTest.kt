package nl.stijnvdpol.klinedash.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardTilesTest {
    private val defaults = listOf("coolant", "intake_temp", "throttle", "load", "timing", "battery")

    /** What the target car reports: 01 03 04 05 06 07 0C 0D 0E 0F 10 11 12 13 14 15 1C 20 21. */
    private val targetCarPids = setOf(
        0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x1C, 0x20, 0x21,
    )

    @Test
    fun `shows the default tiles while the supported PIDs are unknown`() {
        assertEquals(defaults, dashboardTileIds(null))
    }

    @Test
    fun `shows the default tiles in order when the car supports them all`() {
        assertEquals(defaults, dashboardTileIds(targetCarPids))
    }

    @Test
    fun `fills an unsupported tile from the fallbacks`() {
        val withoutThrottle = targetCarPids - 0x11
        assertEquals(
            listOf("coolant", "intake_temp", "load", "timing", "battery", "maf"),
            dashboardTileIds(withoutThrottle),
        )
    }

    @Test
    fun `uses fallbacks in order and keeps the battery without any PID`() {
        val onlyTrims = setOf(0x06, 0x07, 0x10)
        assertEquals(listOf("battery", "maf", "stft_b1", "ltft_b1"), dashboardTileIds(onlyTrims))
    }

    @Test
    fun `demand covers the gauge signals and the tiles`() {
        assertEquals(defaults.toSet() + "rpm" + "speed", dashboardDemandIds(defaults))
    }
}
