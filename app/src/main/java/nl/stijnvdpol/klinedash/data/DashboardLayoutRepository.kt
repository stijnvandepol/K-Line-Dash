package nl.stijnvdpol.klinedash.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

private const val LAYOUT_KEY = "dashboard.layout"

/** Persists the [DashboardLayout] as one JSON row ("dashboard.layout") of the settings table. */
class DashboardLayoutRepository(dao: SettingsDao, decodeDispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private val store = TypedSettings(
        dao,
        { rows -> decodeDashboardLayout(rows[LAYOUT_KEY]) },
        { mapOf(LAYOUT_KEY to encodeDashboardLayout(it)) },
        decodeDispatcher,
    )

    val layout: Flow<DashboardLayout> = store.flow

    suspend fun update(transform: (DashboardLayout) -> DashboardLayout) = store.update(transform)
}
