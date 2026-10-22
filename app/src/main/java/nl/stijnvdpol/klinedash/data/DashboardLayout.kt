package nl.stijnvdpol.klinedash.data

/** The three page templates of docs/design-system.md. */
enum class PageTemplate { MAIN, GRID, TRACK }

/** Cells across by cells down. */
enum class GridSize(val columns: Int, val rows: Int, val code: String) {
    TWO_BY_TWO(2, 2, "2x2"),
    THREE_BY_TWO(3, 2, "3x2"),
    THREE_BY_THREE(3, 3, "3x3"),
    ;

    val cellCount: Int get() = columns * rows
}

enum class WidgetType { NUMBER, GRAPH }

/**
 * What one grid cell shows. [signalId] is kept even when this version's catalog does not know it
 * (a layout from a newer app): the cell then shows "--" instead of losing the choice.
 */
data class GridCell(val signalId: String, val widget: WidgetType = WidgetType.NUMBER)

/** One swipeable page of the dashboard. A page has no name of its own: the template and its position give it one. */
sealed interface Page {
    val template: PageTemplate
}

/** The classic dashboard: three primary fields and a row of six small ones, always with the RPM bar. */
data class MainPage(
    val primary: List<String> = DEFAULT_PRIMARY_IDS,
    val fields: List<String> = DEFAULT_FIELD_IDS,
) : Page {
    override val template get() = PageTemplate.MAIN
}

data class GridPage(
    val size: GridSize = GridSize.THREE_BY_TWO,
    val cells: List<GridCell> = defaultGridCells(size),
) : Page {
    override val template get() = PageTemplate.GRID
}

/** For track days; [shiftRpm] is where the shift light starts to flash. */
data class TrackPage(val shiftRpm: Int = DEFAULT_SHIFT_RPM) : Page {
    override val template get() = PageTemplate.TRACK
}

data class DashboardLayout(val pages: List<Page>) {
    init {
        require(pages.isNotEmpty()) { "A dashboard needs at least one page" }
    }

    companion object {
        /** Today's dashboard first, then the track page and a grid of graphs and numbers. */
        val Default = DashboardLayout(
            listOf(
                MainPage(),
                TrackPage(),
                GridPage(GridSize.THREE_BY_TWO, DEFAULT_GRID_CELLS),
            ),
        )
    }
}

const val PRIMARY_SLOT_COUNT = 3
const val FIELD_SLOT_COUNT = 6
const val MAX_PAGES = 12

private val TRACK_SIGNAL_IDS = listOf("rpm", "speed", "coolant")

const val DEFAULT_SHIFT_RPM = 7_800
const val MIN_SHIFT_RPM = 4_000
const val MAX_SHIFT_RPM = 9_000
const val SHIFT_RPM_STEP = 100

val DEFAULT_PRIMARY_IDS = listOf("rpm", "speed", "coolant")

/** Field order of the wireframe: intake, throttle, load, ignition timing, battery, air flow. */
val DEFAULT_FIELD_IDS = listOf("intake_temp", "throttle", "load", "timing", "battery", "maf")

private val DEFAULT_GRID_CELLS = listOf(
    GridCell("rpm", WidgetType.GRAPH),
    GridCell("speed", WidgetType.GRAPH),
    GridCell("coolant", WidgetType.GRAPH),
    GridCell("stft_b1"),
    GridCell("ltft_b1"),
    GridCell("battery"),
)

/** Signals for the cells of a new grid, and for cells that appear when a grid gets bigger, in this order. */
private val FILL_SIGNAL_IDS = listOf("rpm", "speed", "coolant", "throttle", "intake_temp", "battery", "load", "timing", "maf")

/** Number cells for the cells of [size] from index [skip] on (the ones before it are already filled). */
fun defaultGridCells(size: GridSize, skip: Int = 0): List<GridCell> =
    List(size.cellCount - skip) { GridCell(FILL_SIGNAL_IDS[(skip + it) % FILL_SIGNAL_IDS.size]) }

fun clampShiftRpm(rpm: Int): Int = rpm.coerceIn(MIN_SHIFT_RPM, MAX_SHIFT_RPM)

/** A new page of [template] with its default content. */
fun newPage(template: PageTemplate): Page = when (template) {
    PageTemplate.MAIN -> MainPage()
    PageTemplate.GRID -> GridPage()
    PageTemplate.TRACK -> TrackPage()
}

/** The signals a page shows, in slot order; the RPM bar of Main and Track is not counted. */
fun Page.signalIds(): List<String> = when (this) {
    is MainPage -> primary + fields
    is GridPage -> cells.map { it.signalId }
    is TrackPage -> TRACK_SIGNAL_IDS
}

/** Signals with a graph widget, over all pages: the ones whose history is recorded. */
fun DashboardLayout.graphSignalIds(): Set<String> =
    pages.filterIsInstance<GridPage>().flatMapTo(HashSet()) { page ->
        page.cells.filter { it.widget == WidgetType.GRAPH }.map { it.signalId }
    }
