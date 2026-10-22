package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.data.DashboardLayout
import nl.stijnvdpol.klinedash.data.GridCell
import nl.stijnvdpol.klinedash.data.GridPage
import nl.stijnvdpol.klinedash.data.GridSize
import nl.stijnvdpol.klinedash.data.MainPage
import nl.stijnvdpol.klinedash.data.TrackPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardFieldsTest {
    private val main = DashboardLayout.Default.pages[0] as MainPage
    private val track = DashboardLayout.Default.pages[1] as TrackPage
    private val grid = DashboardLayout.Default.pages[2] as GridPage

    /** What the target car reports: 01 03 04 05 06 07 0C 0D 0E 0F 10 11 12 13 14 15 1C 20 21. */
    private val targetCarPids = setOf(
        0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x1C, 0x20, 0x21,
    )

    @Test
    fun `the main page asks for its nine signals and the rpm of the bar`() {
        val expected = setOf("rpm", "speed", "coolant", "intake_temp", "throttle", "load", "timing", "battery", "maf")
        assertEquals(expected, pageDemandIds(main, targetCarPids))
        assertEquals(expected, pageDemandIds(main, null))
    }

    @Test
    fun `the track page asks for rpm, speed and coolant only`() {
        assertEquals(setOf("rpm", "speed", "coolant"), pageDemandIds(track, targetCarPids))
    }

    @Test
    fun `a grid page asks for its own signals and not for the rpm bar it does not have`() {
        val onlyNumbers = GridPage(GridSize.TWO_BY_TWO, listOf(GridCell("load"), GridCell("map"), GridCell("battery"), GridCell("load")))
        assertEquals(setOf("load", "map", "battery"), pageDemandIds(onlyNumbers, null))
        assertEquals(setOf("rpm", "speed", "coolant", "stft_b1", "ltft_b1", "battery"), pageDemandIds(grid, targetCarPids))
    }

    @Test
    fun `switching pages replaces the demand`() {
        val demands = DashboardLayout.Default.pages.map { pageDemandIds(it, targetCarPids) }
        assertEquals(3, demands.toSet().size)
    }

    @Test
    fun `signals the car does not support are not asked for, the battery needs no PID`() {
        val withoutThrottle = targetCarPids - 0x11
        assertFalse("throttle" in pageDemandIds(main, withoutThrottle))
        assertTrue("battery" in pageDemandIds(main, emptySet()))
        assertEquals(setOf("battery"), pageDemandIds(main, emptySet()) - "rpm")
    }

    @Test
    fun `an id this version does not know is not asked for`() {
        val future = MainPage(primary = listOf("rpm", "hyperdrive", "coolant"))
        assertFalse("hyperdrive" in pageDemandIds(future, null))
    }

    @Test
    fun `only rpm and speed use their label as the unit`() {
        assertTrue(labelIsUnit("rpm"))
        assertTrue(labelIsUnit("speed"))
        assertFalse(labelIsUnit("coolant"))
    }
}
