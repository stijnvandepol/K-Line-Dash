package nl.stijnvdpol.klinedash.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.data.ClusterStyle
import nl.stijnvdpol.klinedash.data.DayNightMode
import nl.stijnvdpol.klinedash.data.ThemeSettings
import nl.stijnvdpol.klinedash.data.ThemeSettingsRepository
import nl.stijnvdpol.klinedash.ui.theme.ThemeCatalog
import nl.stijnvdpol.klinedash.ui.theme.ThemeCatalogSource
import nl.stijnvdpol.klinedash.ui.theme.steppedMinute

private const val STOP_TIMEOUT_MS = 5_000L

/** Which theme the list picks in the automatic modes. */
internal enum class ThemeSlot { DAY, NIGHT }

/** The two schedule times. */
internal enum class ScheduleTime { DAY_START, NIGHT_START }

/**
 * SETUP > Display. Every change is one read-modify-write on the stored settings; the activity's
 * theme host observes the same flow, so a change shows on every screen at once.
 */
internal class DisplayViewModel(
    private val repository: ThemeSettingsRepository,
    private val catalogSource: ThemeCatalogSource,
) : ViewModel() {
    /** Null until the first read from the database. */
    val settings: StateFlow<ThemeSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Null until the theme files are loaded. */
    val catalog: StateFlow<ThemeCatalog?> = catalogSource.catalog

    /** Picks up files the user copied to the themes folder. */
    fun reload() = catalogSource.reload()

    fun setMode(mode: DayNightMode) = update { it.copy(mode = mode) }

    /** In manual mode the one theme; in the automatic modes the theme of [slot]. */
    fun chooseTheme(slot: ThemeSlot, themeId: String) = update {
        when {
            it.mode == DayNightMode.MANUAL -> it.copy(manualThemeId = themeId)
            slot == ThemeSlot.DAY -> it.copy(dayThemeId = themeId)
            else -> it.copy(nightThemeId = themeId)
        }
    }

    /** One 15-minute step of [time]: [direction] 1 is later, -1 is earlier. */
    fun stepSchedule(time: ScheduleTime, direction: Int) = update {
        when (time) {
            ScheduleTime.DAY_START -> it.copy(dayStartMinute = steppedMinute(it.dayStartMinute, it.nightStartMinute, direction))
            ScheduleTime.NIGHT_START -> it.copy(nightStartMinute = steppedMinute(it.nightStartMinute, it.dayStartMinute, direction))
        }
    }

    /** [rgb] is 0xRRGGBB; null goes back to the accent of the theme itself. */
    fun setAccent(rgb: Int?) = update { it.copy(accentOverride = rgb) }

    /** One cluster style for every theme; null follows the cluster of the theme in effect. */
    fun setCluster(style: ClusterStyle?) = update { it.copy(clusterOverride = style) }

    private fun update(transform: (ThemeSettings) -> ThemeSettings) {
        viewModelScope.launch { repository.update(transform) }
    }
}
