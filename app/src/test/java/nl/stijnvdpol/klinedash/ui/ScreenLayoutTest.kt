package nl.stijnvdpol.klinedash.ui

import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.ui.theme.ScreenProfile
import nl.stijnvdpol.klinedash.ui.theme.SizeTier
import nl.stijnvdpol.klinedash.ui.theme.screenProfileOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val SmallPhone = screenProfileOf(640f, 360f)
private val TypicalPhone = screenProfileOf(914f, 411f)
private val SmallUnit = screenProfileOf(1024f, 600f)
private val ReferenceUnit = screenProfileOf(1280f, 720f)
private val WideUnit = screenProfileOf(1920f, 720f)
private val Tablet = screenProfileOf(1333f, 800f)

class ScreenLayoutTest {
    @Test
    fun `setup shows its menu beside the section on every screen but the smallest phone`() {
        assertFalse(isSetupSplit(SmallPhone))
        listOf(TypicalPhone, SmallUnit, ReferenceUnit, WideUnit, Tablet).forEach { assertTrue("$it", isSetupSplit(it)) }
    }

    @Test
    fun `the sensor table is one column on a narrow canvas and two from 1000 dp`() {
        assertEquals(1, sensorColumnCount(SmallPhone))
        assertEquals(2, sensorColumnCount(TypicalPhone))
        assertEquals(2, sensorColumnCount(ReferenceUnit))
        assertEquals(1, sensorColumnCount(ScreenProfile(SizeTier.EXPANDED, 999f, 720f)))
    }

    @Test
    fun `the link status drops the protocol name only on the smallest phone`() {
        assertFalse(showsLinkProtocol(SmallPhone))
        assertTrue(showsLinkProtocol(TypicalPhone))
        assertTrue(showsLinkProtocol(ReferenceUnit))
    }

    @Test
    fun `six small fields stay in one row on every supported screen`() {
        listOf(SmallPhone, TypicalPhone, SmallUnit, ReferenceUnit, WideUnit, Tablet).forEach { assertEquals("$it", 6, smallFieldColumns(it, 6)) }
    }

    @Test
    fun `six small fields wrap into two rows of three when a row would squeeze them`() {
        assertEquals(3, smallFieldColumns(ScreenProfile(SizeTier.COMPACT, 700f, 480f), 6))
        assertEquals(6, smallFieldColumns(ScreenProfile(SizeTier.COMPACT, 780f, 480f), 6))
    }

    @Test
    fun `the warnings stack their steppers below 960 dp and keep one line above`() {
        assertTrue(isWarningsStacked(780f))
        assertTrue(isWarningsStacked(959f))
        assertFalse(isWarningsStacked(960f))
        // What SETUP leaves for a section: the canvas minus the 240 dp menu, or minus the back strip.
        assertTrue(isWarningsStacked(SmallPhone.widthDp - 73f))
        assertFalse(isWarningsStacked(SmallUnit.widthDp - 241f))
        assertFalse(isWarningsStacked(ReferenceUnit.widthDp - 241f))
    }

    @Test
    fun `the session buttons are pinned on a phone and stay in the flow from a small head unit up`() {
        listOf(SmallPhone, TypicalPhone).forEach { assertTrue("$it", areSessionActionsPinned(it)) }
        listOf(SmallUnit, ReferenceUnit, WideUnit, Tablet).forEach { assertFalse("$it", areSessionActionsPinned(it)) }
    }

    @Test
    fun `the lap table drops two columns when it is narrower than 680 dp`() {
        assertFalse(lapTableShowsAllColumns(512f))
        assertTrue(lapTableShowsAllColumns(680f))
        assertTrue(lapTableShowsAllColumns(747f))
    }

    @Test
    fun `a grid of cells keeps two columns from 520 dp and gives each cell a row below`() {
        assertEquals(1, gridColumnsFor(426f))
        assertEquals(2, gridColumnsFor(520f))
        assertEquals(2, gridColumnsFor(ReferenceUnit.widthDp / 2))
    }

    @Test
    fun `trip statistics are three to a row from 570 dp and two below`() {
        assertEquals(2, statColumnsFor(512f))
        assertEquals(3, statColumnsFor(570f))
        assertEquals(3, statColumnsFor(ReferenceUnit.widthDp - 501f))
    }

    @Test
    fun `a side column keeps its design width on a wide screen`() {
        assertEquals(480.dp, sideColumnWidth(1280.dp, 0.42f, 340.dp, 480.dp))
        assertEquals(480.dp, sideColumnWidth(1920.dp, 0.42f, 340.dp, 480.dp))
    }

    @Test
    fun `a side column takes its share of a narrow screen and never less than its minimum`() {
        assertEquals(420.dp, sideColumnWidth(1000.dp, 0.42f, 340.dp, 480.dp))
        assertEquals(340.dp, sideColumnWidth(600.dp, 0.42f, 340.dp, 480.dp))
        assertEquals(300.dp, sideColumnWidth(1000.dp, 0.3f, 200.dp, 480.dp))
    }

    @Test
    fun `a short screen gives the rpm bar and the small fields less height`() {
        val compact = dashMetricsOf(SizeTier.COMPACT)
        val reference = dashMetricsOf(SizeTier.EXPANDED)
        assertTrue(compact.barHeight < reference.barHeight)
        assertTrue(compact.smallFieldRowHeight < reference.smallFieldRowHeight)
        assertEquals(reference, dashMetricsOf(SizeTier.MEDIUM))
    }

    @Test
    fun `the reference unit keeps the design sizes`() {
        val metrics = dashMetricsOf(ReferenceUnit.tier)
        assertEquals(96.dp, metrics.barHeight)
        assertEquals(104.dp, metrics.smallFieldRowHeight)
        assertEquals(12.dp, metrics.barPaddingTop)
    }
}
