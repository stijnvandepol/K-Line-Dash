package nl.stijnvdpol.klinedash.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val DEFAULT_SHOW_MS = 6_000L

/** A short message that goes away by itself: a refused action, the result of a tap. A new message replaces the old one. */
internal class TimedMessage(private val scope: CoroutineScope, private val showMs: Long = DEFAULT_SHOW_MS) {
    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text.asStateFlow()
    private var job: Job? = null

    fun show(message: String) {
        _text.value = message
        job?.cancel()
        job = scope.launch {
            delay(showMs)
            _text.value = null
        }
    }
}
