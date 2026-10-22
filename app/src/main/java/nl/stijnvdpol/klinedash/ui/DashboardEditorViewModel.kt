package nl.stijnvdpol.klinedash.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.AppGraph
import nl.stijnvdpol.klinedash.data.DashboardLayout
import nl.stijnvdpol.klinedash.data.DashboardLayoutRepository
import nl.stijnvdpol.klinedash.data.GridCell
import nl.stijnvdpol.klinedash.data.GridPage
import nl.stijnvdpol.klinedash.data.GridSize
import nl.stijnvdpol.klinedash.data.MAX_PAGES
import nl.stijnvdpol.klinedash.data.MainPage
import nl.stijnvdpol.klinedash.data.Page
import nl.stijnvdpol.klinedash.data.PageTemplate
import nl.stijnvdpol.klinedash.data.SHIFT_RPM_STEP
import nl.stijnvdpol.klinedash.data.TrackPage
import nl.stijnvdpol.klinedash.data.resizedTo
import nl.stijnvdpol.klinedash.data.withAddedPage
import nl.stijnvdpol.klinedash.data.withCell
import nl.stijnvdpol.klinedash.data.withMovedPage
import nl.stijnvdpol.klinedash.data.withPageChanged
import nl.stijnvdpol.klinedash.data.withShiftRpm
import nl.stijnvdpol.klinedash.data.withSignal
import nl.stijnvdpol.klinedash.data.withoutPage

private const val STOP_TIMEOUT_MS = 5_000L

/**
 * SETUP > Dashboard. Every change is one read-modify-write on the stored layout, which the pager
 * on the dashboard picks up from the same flow. The selected page is not stored.
 */
internal class DashboardEditorViewModel(
    private val repository: DashboardLayoutRepository,
    supportedPidsFlow: Flow<Set<Int>?>,
) : ViewModel() {
    /** Null until the first read from the database. */
    val layout: StateFlow<DashboardLayout?> = repository.layout
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Null while the car has not reported which PIDs it supports (or there is no connection). */
    val supportedPids: StateFlow<Set<Int>?> =
        supportedPidsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _selectedPage = MutableStateFlow(0)

    /** May be past the last page right after a delete; the screen coerces it. */
    val selectedPage: StateFlow<Int> = _selectedPage.asStateFlow()

    fun selectPage(index: Int) {
        _selectedPage.value = index
    }

    fun addPage(template: PageTemplate) {
        val count = layout.value?.pages?.size ?: return
        if (count >= MAX_PAGES) return
        _selectedPage.value = count
        edit { it.withAddedPage(template) }
    }

    fun deleteSelectedPage() {
        val index = _selectedPage.value
        _selectedPage.value = (index - 1).coerceAtLeast(0)
        edit { it.withoutPage(index) }
    }

    /** [direction] -1 moves the selected page left, 1 right; the selection follows the page. */
    fun moveSelectedPage(direction: Int) {
        val index = _selectedPage.value
        val count = layout.value?.pages?.size ?: return
        if (index + direction !in 0 until count) return
        _selectedPage.value = index + direction
        edit { it.withMovedPage(index, direction) }
    }

    fun setMainSignal(slot: Int, signalId: String) = editSelected { page ->
        if (page is MainPage) page.withSignal(slot, signalId) else page
    }

    fun setGridCell(index: Int, cell: GridCell) = editSelected { page ->
        if (page is GridPage) page.withCell(index, cell) else page
    }

    fun setGridSize(size: GridSize) = editSelected { page ->
        if (page is GridPage) page.resizedTo(size) else page
    }

    /** One step of the shift point: [direction] 1 raises it by 100 rpm, -1 lowers it. */
    fun stepShiftRpm(direction: Int) = editSelected { page ->
        if (page is TrackPage) page.withShiftRpm(page.shiftRpm + direction * SHIFT_RPM_STEP) else page
    }

    private fun editSelected(change: (Page) -> Page) {
        val index = _selectedPage.value
        edit { it.withPageChanged(index, change) }
    }

    private fun edit(transform: (DashboardLayout) -> DashboardLayout) {
        viewModelScope.launch { repository.update(transform) }
    }

    companion object {
        fun forGraph(graph: AppGraph) = DashboardEditorViewModel(
            graph.dashboardLayoutRepository,
            pollerFlow<Set<Int>?>(null) { it.supportedPids },
        )
    }
}
