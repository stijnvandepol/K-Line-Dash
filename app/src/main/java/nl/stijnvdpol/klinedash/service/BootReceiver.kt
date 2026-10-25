package nl.stijnvdpol.klinedash.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import nl.stijnvdpol.klinedash.appGraph

private const val TAG = "BootReceiver"

/** Well below the ten seconds a broadcast may stay open with goAsync before the system ANRs the app. */
private const val BOOT_WORK_TIMEOUT_MS = 8_000L

/**
 * Starts [ObdService] after BOOT_COMPLETED or an app update, when "Start with the head unit" is on.
 * Those two are the only broadcasts a manifest receiver may start a foreground service from on
 * Android 12+; [planBoot] holds the decision. The settings are read off the main thread, so the
 * broadcast is kept open with goAsync until the service has been asked to start, for at most
 * [BOOT_WORK_TIMEOUT_MS] (a database that does not open at boot must not turn into an ANR).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val app = context.applicationContext
        val pending = goAsync()
        val log = CoroutineExceptionHandler { _, e -> Log.e(TAG, "Start after $action failed", e) }
        app.appGraph.applicationScope.launch(log) {
            try {
                withTimeout(BOOT_WORK_TIMEOUT_MS) {
                    val settings = app.appGraph.startupSettingsRepository.settings.first()
                    val plan = planBoot(action, settings, Settings.canDrawOverlays(app))
                    Log.i(TAG, "$action: $plan")
                    if (plan.startService) ObdService.start(app)
                    if (plan.openDashboard) openDashboard(app)
                }
            } catch (e: TimeoutCancellationException) {
                // The service is not started this boot; the user opens the app as before.
                Log.e(TAG, "Start after $action took longer than $BOOT_WORK_TIMEOUT_MS ms, giving up", e)
            } finally {
                pending.finish()
            }
        }
    }

    // Allowed from the background only because the user granted "display over other apps".
    private fun openDashboard(context: Context) {
        context.startActivity(ObdService.dashboardIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
