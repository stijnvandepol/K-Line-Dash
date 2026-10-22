package nl.stijnvdpol.klinedash.data

import nl.stijnvdpol.klinedash.core.obd.Signals
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardLayoutDefaultTest {
    private val default = DashboardLayout.Default

    @Test
    fun `starts with todays dashboard, then track and a grid`() {
        assertEquals(listOf(PageTemplate.MAIN, PageTemplate.TRACK, PageTemplate.GRID), default.pages.map { it.template })
        val main = default.pages[0] as MainPage
        assertEquals(listOf("rpm", "speed", "coolant"), main.primary)
        assertEquals(listOf("intake_temp", "throttle", "load", "timing", "battery", "maf"), main.fields)
        assertEquals(7_800, (default.pages[1] as TrackPage).shiftRpm)
    }

    @Test
    fun `the default grid has graphs for rpm, speed and coolant and numbers for the trims and battery`() {
        val grid = default.pages[2] as GridPage
        assertEquals(GridSize.THREE_BY_TWO, grid.size)
        assertEquals(
            listOf(
                GridCell("rpm", WidgetType.GRAPH), GridCell("speed", WidgetType.GRAPH), GridCell("coolant", WidgetType.GRAPH),
                GridCell("stft_b1", WidgetType.NUMBER), GridCell("ltft_b1", WidgetType.NUMBER), GridCell("battery", WidgetType.NUMBER),
            ),
            grid.cells,
        )
    }

    @Test
    fun `every signal of the default layout exists in the catalog`() {
        val unknown = default.pages.flatMap { it.signalIds() }.filter { Signals.byId(it) == null }
        assertEquals(emptyList<String>(), unknown)
    }

    @Test
    fun `only the signals with a graph are recorded`() {
        assertEquals(setOf("rpm", "speed", "coolant"), default.graphSignalIds())
    }

    @Test
    fun `a new page of each template has the slots its template needs`() {
        assertEquals(PRIMARY_SLOT_COUNT + FIELD_SLOT_COUNT, newPage(PageTemplate.MAIN).signalIds().size)
        assertEquals(GridSize.THREE_BY_TWO.cellCount, newPage(PageTemplate.GRID).signalIds().size)
        assertEquals(DEFAULT_SHIFT_RPM, (newPage(PageTemplate.TRACK) as TrackPage).shiftRpm)
    }

    @Test
    fun `default cells exist in the catalog for the biggest grid`() {
        val ids = defaultGridCells(GridSize.THREE_BY_THREE).map { it.signalId }
        assertEquals(GridSize.THREE_BY_THREE.cellCount, ids.size)
        assertTrue(ids.all { Signals.byId(it) != null })
    }
}

class DashboardLayoutJsonTest {
    private val custom = DashboardLayout(
        listOf(
            MainPage(primary = listOf("rpm", "throttle", "map"), fields = listOf("a_future_signal", "load", "timing", "battery", "maf", "stft_b1")),
            GridPage(GridSize.TWO_BY_TWO, listOf(GridCell("rpm", WidgetType.GRAPH), GridCell("speed"), GridCell("o2_b1s1_v", WidgetType.GRAPH), GridCell("battery"))),
            GridPage(GridSize.THREE_BY_THREE),
            TrackPage(6_500),
        ),
    )

    @Test
    fun `the default layout survives an encode and decode`() {
        assertEquals(DashboardLayout.Default, decodeDashboardLayout(encodeDashboardLayout(DashboardLayout.Default)))
    }

    @Test
    fun `a custom layout survives an encode and decode, unknown signal ids included`() {
        assertEquals(custom, decodeDashboardLayout(encodeDashboardLayout(custom)))
    }

    @Test
    fun `nothing stored gives the default layout`() {
        assertSame(DashboardLayout.Default, decodeDashboardLayout(null))
    }

    @Test
    fun `text that is not JSON gives the default layout`() {
        assertSame(DashboardLayout.Default, decodeDashboardLayout("{\"pages\": [ oops"))
        assertSame(DashboardLayout.Default, decodeDashboardLayout(""))
        assertSame(DashboardLayout.Default, decodeDashboardLayout("[]"))
    }

    @Test
    fun `JSON without pages, with no pages or with only unusable pages gives the default layout`() {
        assertSame(DashboardLayout.Default, decodeDashboardLayout("{}"))
        assertSame(DashboardLayout.Default, decodeDashboardLayout("{\"pages\": []}"))
        assertSame(DashboardLayout.Default, decodeDashboardLayout("{\"pages\": [{\"template\": \"hologram\"}, 5, null]}"))
        assertSame(DashboardLayout.Default, decodeDashboardLayout("{\"pages\": \"main\"}"))
    }

    @Test
    fun `a page with an unknown template is skipped and the others are kept`() {
        val layout = decodeDashboardLayout(
            """{"pages": [{"template": "track", "shiftRpm": 7000}, {"template": "hologram"}, {"template": "grid", "size": "9x9"}]}""",
        )
        assertEquals(DashboardLayout(listOf(TrackPage(7_000))), layout)
    }

    @Test
    fun `main slots are cut or filled up to three primary and six small fields`() {
        val layout = decodeDashboardLayout(
            """{"pages": [{"template": "main", "primary": ["load", "", "map", "extra"], "fields": ["throttle"]}]}""",
        )
        val main = layout.pages.single() as MainPage
        assertEquals(listOf("load", "speed", "map"), main.primary)
        assertEquals(listOf("throttle") + DEFAULT_FIELD_IDS.drop(1), main.fields)
        assertEquals(FIELD_SLOT_COUNT, main.fields.size)
    }

    @Test
    fun `grid cells are cut or filled up to the size, a bad cell gets a default and an unknown widget becomes a number`() {
        val layout = decodeDashboardLayout(
            """{"pages": [{"template": "grid", "size": "2x2", "cells": [
                {"signal": "map", "widget": "graph"}, {"widget": "graph"}, {"signal": "load", "widget": "sparkles"}]}]}""",
        )
        val grid = layout.pages.single() as GridPage
        val defaults = defaultGridCells(GridSize.TWO_BY_TWO)
        assertEquals(listOf(GridCell("map", WidgetType.GRAPH), defaults[1], GridCell("load", WidgetType.NUMBER), defaults[3]), grid.cells)
    }

    @Test
    fun `the shift point is clamped to its range`() {
        val low = decodeDashboardLayout("""{"pages": [{"template": "track", "shiftRpm": 100}]}""").pages.single() as TrackPage
        val high = decodeDashboardLayout("""{"pages": [{"template": "track", "shiftRpm": 99999}]}""").pages.single() as TrackPage
        val missing = decodeDashboardLayout("""{"pages": [{"template": "track"}]}""").pages.single() as TrackPage
        assertEquals(MIN_SHIFT_RPM, low.shiftRpm)
        assertEquals(MAX_SHIFT_RPM, high.shiftRpm)
        assertEquals(DEFAULT_SHIFT_RPM, missing.shiftRpm)
    }

    @Test
    fun `no more pages than the maximum are read`() {
        val page = """{"template": "track"}"""
        val layout = decodeDashboardLayout("""{"pages": [${List(MAX_PAGES + 5) { page }.joinToString(",")}]}""")
        assertEquals(MAX_PAGES, layout.pages.size)
    }

    @Test
    fun `the stored text names the template and the widget in lower case`() {
        val json = JSONObject(encodeDashboardLayout(DashboardLayout.Default))
        assertEquals(1, json.getInt("version"))
        assertEquals("main", json.getJSONArray("pages").getJSONObject(0).getString("template"))
        val cell = json.getJSONArray("pages").getJSONObject(2).getJSONArray("cells").getJSONObject(0)
        assertEquals("graph", cell.getString("widget"))
        assertNotNull(cell.getString("signal"))
    }
}

class DashboardLayoutEditTest {
    private val three = DashboardLayout.Default

    @Test
    fun `a page is added at the end up to the maximum`() {
        val added = three.withAddedPage(PageTemplate.TRACK)
        assertEquals(4, added.pages.size)
        assertEquals(PageTemplate.TRACK, added.pages.last().template)
        var full = three
        repeat(MAX_PAGES) { full = full.withAddedPage(PageTemplate.MAIN) }
        assertEquals(MAX_PAGES, full.pages.size)
    }

    @Test
    fun `the last page cannot be deleted`() {
        val one = three.withoutPage(0).withoutPage(0)
        assertEquals(1, one.pages.size)
        assertSame(one, one.withoutPage(0))
        assertSame(three, three.withoutPage(7))
    }

    @Test
    fun `a page moves one place left or right and stays put at the ends`() {
        val moved = three.withMovedPage(0, 1)
        assertEquals(listOf(PageTemplate.TRACK, PageTemplate.MAIN, PageTemplate.GRID), moved.pages.map { it.template })
        assertSame(three, three.withMovedPage(0, -1))
        assertSame(three, three.withMovedPage(2, 1))
    }

    @Test
    fun `a main slot is replaced, primary slots first`() {
        val main = (three.pages[0] as MainPage).withSignal(1, "load").withSignal(8, "map")
        assertEquals(listOf("rpm", "load", "coolant"), main.primary)
        assertEquals("map", main.fields.last())
        assertEquals("map", main.signalAt(8))
        assertEquals(main, main.withSignal(9, "x"))
    }

    @Test
    fun `a grid keeps its cells when it grows and gets default numbers for the new ones`() {
        val grid = three.pages[2] as GridPage
        val bigger = grid.resizedTo(GridSize.THREE_BY_THREE)
        assertEquals(9, bigger.cells.size)
        assertEquals(grid.cells, bigger.cells.take(6))
        assertEquals(defaultGridCells(GridSize.THREE_BY_THREE).drop(6), bigger.cells.drop(6))
    }

    @Test
    fun `a grid keeps the first cells when it shrinks`() {
        val grid = three.pages[2] as GridPage
        val smaller = grid.resizedTo(GridSize.TWO_BY_TWO)
        assertEquals(grid.cells.take(4), smaller.cells)
    }

    @Test
    fun `a cell is replaced by index`() {
        val grid = (three.pages[2] as GridPage).withCell(4, GridCell("map", WidgetType.GRAPH))
        assertEquals(GridCell("map", WidgetType.GRAPH), grid.cells[4])
        assertEquals(grid, grid.withCell(6, GridCell("x")))
    }

    @Test
    fun `the shift point stays inside its range`() {
        assertEquals(MAX_SHIFT_RPM, TrackPage(MAX_SHIFT_RPM).withShiftRpm(MAX_SHIFT_RPM + SHIFT_RPM_STEP).shiftRpm)
        assertEquals(MIN_SHIFT_RPM, TrackPage(MIN_SHIFT_RPM).withShiftRpm(MIN_SHIFT_RPM - SHIFT_RPM_STEP).shiftRpm)
        assertEquals(7_900, TrackPage(7_800).withShiftRpm(7_900).shiftRpm)
    }

    @Test
    fun `every edit leaves a layout that encodes and decodes to itself`() {
        val edited = three.withAddedPage(PageTemplate.GRID)
            .withPageChanged(3) { (it as GridPage).resizedTo(GridSize.TWO_BY_TWO).withCell(0, GridCell("map")) }
            .withMovedPage(3, -1)
        assertEquals(edited, decodeDashboardLayout(encodeDashboardLayout(edited)))
    }
}
