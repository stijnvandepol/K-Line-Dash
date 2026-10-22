package nl.stijnvdpol.klinedash.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import nl.stijnvdpol.klinedash.core.alert.Alert

private const val STOP_TIMEOUT_MS = 5_000L

/**
 * The warnings overlay of every page: active alerts from the service plus which banners the user
 * has hidden. Lives in the activity, so a hidden banner stays hidden when the page changes.
 */
internal class AlertOverlayViewModel(alerts: StateFlow<List<Alert>>) : ViewModel() {
    private val dismissals = MutableStateFlow<Dismissals>(emptyMap())

    val overlay: StateFlow<AlertOverlay> = combine(alerts, dismissals, ::alertOverlay)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AlertOverlay())

    fun dismiss(alert: Alert) = dismissals.update { withDismissed(it, alert) }
}
