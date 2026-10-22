package nl.stijnvdpol.klinedash.ui.pages

import nl.stijnvdpol.klinedash.data.DashboardLayout
import nl.stijnvdpol.klinedash.data.GridPage
import nl.stijnvdpol.klinedash.data.MainPage
import nl.stijnvdpol.klinedash.data.TrackPage
import org.junit.Assert.assertEquals
import org.junit.Test

class PageNamesTest {
    @Test
    fun `a template that occurs once has no number`() {
        assertEquals(listOf(null, null, null), templateNumbers(DashboardLayout.Default.pages))
    }

    @Test
    fun `pages of the same template are numbered in page order`() {
        val pages = listOf(GridPage(), MainPage(), GridPage(), TrackPage(), GridPage())
        assertEquals(listOf(1, null, 2, null, 3), templateNumbers(pages))
    }

    @Test
    fun `the title is the template name and the number when there is one`() {
        assertEquals("Grid", pageTitle("Grid", null))
        assertEquals("Grid 2", pageTitle("Grid", 2))
    }
}
