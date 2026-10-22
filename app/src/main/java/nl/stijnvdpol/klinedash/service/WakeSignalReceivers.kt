package nl.stijnvdpol.klinedash.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * The dynamic receivers of [ObdService] (vendor ACC broadcasts cannot be declared in the manifest on
 * Android 8+, and a force-stopped app gets none of them anyway). Two registrations:
 * - the system's own SCREEN_ON and USER_PRESENT, not exported: only the system may send them;
 * - the vendor actions from the settings, exported, because the sender is another app.
 *
 * Every received action goes to [onSignal] on the main thread. Call [close] when the service ends.
 */
internal class WakeSignalReceivers(private val context: Context, onSignal: (String) -> Unit) {
    private val systemReceiver = SignalReceiver(onSignal)
    private val vendorReceiver = SignalReceiver(onSignal)
    private var vendorActions: Set<String> = emptySet()

    init {
        val screen = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(context, systemReceiver, screen, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Listens for exactly [actions]; a repeated call with the same set does nothing. */
    fun setVendorActions(actions: Collection<String>) {
        val wanted = actions.toSet()
        if (wanted == vendorActions) return
        if (vendorActions.isNotEmpty()) context.unregisterReceiver(vendorReceiver)
        vendorActions = wanted
        if (wanted.isEmpty()) return
        val filter = IntentFilter().apply { wanted.forEach(::addAction) }
        ContextCompat.registerReceiver(context, vendorReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun close() {
        context.unregisterReceiver(systemReceiver)
        if (vendorActions.isNotEmpty()) context.unregisterReceiver(vendorReceiver)
        vendorActions = emptySet()
    }

    private class SignalReceiver(private val onSignal: (String) -> Unit) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.action?.let(onSignal)
        }
    }
}
