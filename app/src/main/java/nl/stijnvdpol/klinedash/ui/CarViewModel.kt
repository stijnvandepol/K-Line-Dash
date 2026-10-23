package nl.stijnvdpol.klinedash.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.core.car.EngineNotes
import nl.stijnvdpol.klinedash.data.CarSettings
import nl.stijnvdpol.klinedash.data.CarSettingsRepository
import nl.stijnvdpol.klinedash.data.DEFAULT_REDLINE_RPM
import nl.stijnvdpol.klinedash.data.MakeChoice
import nl.stijnvdpol.klinedash.data.REDLINE_STEP_RPM
import nl.stijnvdpol.klinedash.data.TOYOTA_2ZZ_GE_REDLINE_RPM
import nl.stijnvdpol.klinedash.data.clampRedline

private const val STOP_TIMEOUT_MS = 5_000L

/** The red line one step ([direction] 1 raises it, -1 lowers it), kept within the allowed range. */
internal fun steppedRedline(redlineRpm: Int, direction: Int): Int = clampRedline(redlineRpm + direction * REDLINE_STEP_RPM)

/** Whether a step in [direction] still changes the red line; false at either end of the range. */
internal fun canStepRedline(redlineRpm: Int, direction: Int): Boolean = steppedRedline(redlineRpm, direction) != redlineRpm

/**
 * The notes of [notes] switched on or off. Switching on the 2ZZ-GE also brings that engine's red
 * line, but only while the user has not set one of their own: a red line that differs from the
 * default is never overwritten. Switching off leaves the red line alone.
 */
internal fun CarSettings.withEngineNotes(notes: EngineNotes, isOn: Boolean): CarSettings {
    if (!isOn) return copy(engineNotes = engineNotes.takeUnless { it == notes })
    val brings2zzRedline = notes == EngineNotes.TOYOTA_2ZZ_GE && redlineRpm == DEFAULT_REDLINE_RPM
    return copy(engineNotes = notes, redlineRpm = if (brings2zzRedline) TOYOTA_2ZZ_GE_REDLINE_RPM else redlineRpm)
}

/**
 * SETUP > Car, and the red line row of the setup wizard. Every change is one read-modify-write on
 * the stored settings, so quick taps on the stepper add up; the tachometers and the trouble code
 * texts pick the new values up from the same flow. The VIN detection behind "Automatic" is written
 * by the VIN detector and the diagnostics read, not from here.
 */
internal class CarViewModel(private val repository: CarSettingsRepository) : ViewModel() {
    /** Null until the first read from the database. */
    val settings: StateFlow<CarSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun stepRedline(direction: Int) = update { it.copy(redlineRpm = steppedRedline(it.redlineRpm, direction)) }

    fun chooseMake(choice: MakeChoice) = update { it.copy(makeChoice = choice) }

    fun setEngineNotes(notes: EngineNotes, isOn: Boolean) = update { it.withEngineNotes(notes, isOn) }

    private fun update(transform: (CarSettings) -> CarSettings) {
        viewModelScope.launch { repository.update(transform) }
    }
}
