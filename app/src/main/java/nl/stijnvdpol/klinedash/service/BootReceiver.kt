package nl.stijnvdpol.klinedash.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.appGraph
import nl.stijnvdpol.klinedash.ui.MainActivity

private const val TAG = "BootReceiver"

/**
 * Starts [ObdService] after BOOT_COMPLETED or an app update, when "Start with the head unit" is on.
 * Those two are the only broadcasts a manifest receiver may start a foreground service from on
 * Android 12+; [planBoot] holds the decision. The settings are read off the main thread, so the
 * broadcast is kept open with goAsync until the service has been asked to start.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val app = context.applicationContext
        val pending = goAsync()
        val log = CoroutineExceptionHandler { _, e -> Log.e(TAG, "Start after $action failed", e) }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + log).launch {
            try {
                val settings = app.appGraph.startupSettingsRepository.settings.first()
                val plan = planBoot(action, settings, Settings.canDrawOverlays(app))
                Log.i(TAG, "$action: $plan")
                if (plan.startService) ObdService.start(app)
                if (plan.openDashboard) openDashboard(app)
            } finally {
                pending.finish()
            }
        }
    }

    // Allowed from the background only because the user granted "display over other apps".
    private fun openDashboard(context: Context) {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
