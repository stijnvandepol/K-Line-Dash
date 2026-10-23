package nl.stijnvdpol.klinedash.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.data.CarSettings
import nl.stijnvdpol.klinedash.data.CarSettingsRepository
import nl.stijnvdpol.klinedash.data.DEFAULT_REDLINE_RPM
import nl.stijnvdpol.klinedash.data.EngineProfile
import nl.stijnvdpol.klinedash.data.REDLINE_STEP_RPM
import nl.stijnvdpol.klinedash.data.TOYOTA_2ZZ_GE_REDLINE_RPM
import nl.stijnvdpol.klinedash.data.clampRedline

private const val STOP_TIMEOUT_MS = 5_000L

/** The red line one step ([direction] 1 raises it, -1 lowers it), kept within the allowed range. */
internal fun steppedRedline(redlineRpm: Int, direction: Int): Int = clampRedline(redlineRpm + direction * REDLINE_STEP_RPM)

/** Whether a step in [direction] still changes the red line; false at either end of the range. */
internal fun canStepRedline(redlineRpm: Int, direction: Int): Boolean = steppedRedline(redlineRpm, direction) != redlineRpm

/**
 * [profile] chosen. The Toyota profile also brings that engine's red line, but only while the user
 * has not set one of their own: a red line that differs from the default is never overwritten.
 */
internal fun CarSettings.withEngineProfile(profile: EngineProfile): CarSettings {
    val brings2zzRedline = profile == EngineProfile.TOYOTA_2ZZ_GE && redlineRpm == DEFAULT_REDLINE_RPM
    return copy(engineProfile = profile, redlineRpm = if (brings2zzRedline) TOYOTA_2ZZ_GE_REDLINE_RPM else redlineRpm)
}

/**
 * SETUP > Car, and the red line row of the setup wizard. Every change is one read-modify-write on
 * the stored settings, so quick taps on the stepper add up; the tachometers and the trouble code
 * texts pick the new values up from the same flow.
 */
internal class CarViewModel(private val repository: CarSettingsRepository) : ViewModel() {
    /** Null until the first read from the database. */
    val settings: StateFlow<CarSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun stepRedline(direction: Int) = update { it.copy(redlineRpm = steppedRedline(it.redlineRpm, direction)) }

    fun chooseProfile(profile: EngineProfile) = update { it.withEngineProfile(profile) }

    private fun update(transform: (CarSettings) -> CarSettings) {
        viewModelScope.launch { repository.update(transform) }
    }
}
