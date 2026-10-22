package nl.stijnvdpol.klinedash.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.core.alert.AlertKind
import nl.stijnvdpol.klinedash.core.alert.AlertSettings
import nl.stijnvdpol.klinedash.data.AlertSettingsRepository
import nl.stijnvdpol.klinedash.data.UnitSystem

private const val STOP_TIMEOUT_MS = 5_000L

/**
 * SETUP > Warnings. Every change is one read-modify-write on the stored settings, so quick taps on
 * a stepper add up; the service and the dashboard colours pick the new values up from the same flow.
 */
internal class WarningsViewModel(private val repository: AlertSettingsRepository) : ViewModel() {
    /** Null until the first read from the database. */
    val settings: StateFlow<AlertSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun setEnabled(kind: AlertKind, isOn: Boolean) =
        update { it.copy(enabled = if (isOn) it.enabled + kind else it.enabled - kind) }

    fun setSound(kind: AlertKind, isOn: Boolean) =
        update { it.copy(withSound = if (isOn) it.withSound + kind else it.withSound - kind) }

    /** One step of [limit] in the unit the user sees ([units]): [direction] 1 raises it, -1 lowers it. */
    fun step(limit: AlertLimit, direction: Int, units: UnitSystem) = update { it.stepped(limit, direction, units) }

    fun resetToDefaults() = update { AlertSettings() }

    private fun update(transform: (AlertSettings) -> AlertSettings) {
        viewModelScope.launch { repository.update(transform) }
    }
}
