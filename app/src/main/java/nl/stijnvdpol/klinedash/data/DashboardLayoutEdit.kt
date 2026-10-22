package nl.stijnvdpol.klinedash.data

/*
 * Edits of the dashboard layout as pure functions. Every result is a valid layout again (at least
 * one page, at most MAX_PAGES, slot counts as the template needs), so the editor can persist it as is.
 */

/** Appended at the end; unchanged when [MAX_PAGES] is reached. */
fun DashboardLayout.withAddedPage(template: PageTemplate): DashboardLayout =
    if (pages.size >= MAX_PAGES) this else DashboardLayout(pages + newPage(template))

/** The last remaining page cannot be removed: the dashboard always has something to show. */
fun DashboardLayout.withoutPage(index: Int): DashboardLayout =
    if (pages.size <= 1 || index !in pages.indices) this else DashboardLayout(pages.filterIndexed { i, _ -> i != index })

/** [direction] -1 moves the page left (earlier), 1 right; at either end nothing changes. */
fun DashboardLayout.withMovedPage(index: Int, direction: Int): DashboardLayout {
    val target = index + direction
    if (index !in pages.indices || target !in pages.indices) return this
    val moved = pages.toMutableList()
    moved[index] = pages[target]
    moved[target] = pages[index]
    return DashboardLayout(moved)
}

fun DashboardLayout.withPageChanged(index: Int, change: (Page) -> Page): DashboardLayout =
    if (index !in pages.indices) this else DashboardLayout(pages.mapIndexed { i, page -> if (i == index) change(page) else page })

/** Slots of a Main page: the three primary fields (0-2), then the six small ones (3-8). */
const val MAIN_SLOT_COUNT = PRIMARY_SLOT_COUNT + FIELD_SLOT_COUNT

fun MainPage.withSignal(slot: Int, signalId: String): MainPage = when (slot) {
    in 0 until PRIMARY_SLOT_COUNT -> copy(primary = primary.replacedAt(slot, signalId))
    in PRIMARY_SLOT_COUNT until MAIN_SLOT_COUNT -> copy(fields = fields.replacedAt(slot - PRIMARY_SLOT_COUNT, signalId))
    else -> this
}

fun MainPage.signalAt(slot: Int): String? = (primary + fields).getOrNull(slot)

fun GridPage.withCell(index: Int, cell: GridCell): GridPage =
    if (index !in cells.indices) this else copy(cells = cells.replacedAt(index, cell))

/** Keeps the cells that still exist; new cells get default numbers. */
fun GridPage.resizedTo(newSize: GridSize): GridPage =
    GridPage(newSize, cells.take(newSize.cellCount) + defaultGridCells(newSize, skip = minOf(cells.size, newSize.cellCount)))

fun TrackPage.withShiftRpm(rpm: Int): TrackPage = copy(shiftRpm = clampShiftRpm(rpm))

private fun <T> List<T>.replacedAt(index: Int, value: T): List<T> = mapIndexed { i, old -> if (i == index) value else old }
