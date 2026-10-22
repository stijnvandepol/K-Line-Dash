package nl.stijnvdpol.klinedash.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.data.StartupSettings
import nl.stijnvdpol.klinedash.data.StartupSettingsRepository
import nl.stijnvdpol.klinedash.data.WakeDirection

private const val STOP_TIMEOUT_MS = 5_000L

/**
 * SETUP > Startup. Each change is one read-modify-write on the stored settings; the service picks
 * the new values up from the same flow (and re-registers its broadcast receivers when the lists change).
 */
internal class StartupSettingsViewModel(private val repository: StartupSettingsRepository) : ViewModel() {
    /** Null until the first read from the database. */
    val settings: StateFlow<StartupSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun setStartOnBoot(isOn: Boolean) = update { it.copy(startOnBoot = isOn) }

    fun setReconnectOnWake(isOn: Boolean) = update { it.copy(reconnectOnWake = isOn) }

    fun setOpenDashboardOnStart(isOn: Boolean) = update { it.copy(openDashboardOnStart = isOn) }

    /** The caller has checked the text with `actionProblem`; an invalid one changes nothing. */
    fun addAction(direction: WakeDirection, action: String) = update { it.withAction(direction, action) }

    fun removeAction(action: String) = update { it.withoutAction(action) }

    fun resetActions() = update { it.withDefaultActions() }

    private fun update(transform: (StartupSettings) -> StartupSettings) {
        viewModelScope.launch { repository.update(transform) }
    }
}
