package nl.stijnvdpol.klinedash.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.appGraph
import nl.stijnvdpol.klinedash.core.elm.ElmConnectionManager
import nl.stijnvdpol.klinedash.data.ConnectionSettings
import nl.stijnvdpol.klinedash.ui.MainActivity

/**
 * Foreground service that owns the one [ElmConnectionManager]. It is independent of the UI: the
 * activity may be killed and recreated while the link stays up; the UI finds the link through
 * [ObdServiceState].
 *
 * Decision (phase 1): no partial wake lock. The foreground service keeps the process alive and the
 * link is idle-light; a head unit that sleeps the CPU also powers down the ELM adapter's USB/BT
 * rail in practice. Revisit when polling runs with the screen off (phase 5).
 */
class ObdService : LifecycleService() {

    /** Bumped by ACTION_CONNECT to rebuild the link even though the settings did not change. */
    private val restartTicks = MutableStateFlow(0)
    private var linkJob: Job? = null
    private var foregroundStarted = false
    private var lastText = ""

    override fun onCreate() {
        super.onCreate()
        createChannel()
        lastText = getString(R.string.state_idle)
        if (!enterForeground(buildNotification(lastText))) {
            // E.g. started from the background on API 31+ after a sticky restart: give up quietly.
            stopSelf()
            return
        }
        linkJob = lifecycleScope.launch {
            // Only a different adapter rebuilds the link: toggling autoConnect, the stored device name
            // (written as a separate row right after the address) or another kind's fields must not
            // drop a running connection or abort one that is being set up.
            val settings = appGraph.settingsRepository.connectionSettings
                .distinctUntilChangedBy { TransportFactory.adapterKey(it) }
            combine(settings, restartTicks) { s, _ -> s }.collectLatest { runLink(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!foregroundStarted) return START_NOT_STICKY
        // startForegroundService() obliges every start to end in startForeground(), also when already running.
        enterForeground(buildNotification(lastText))
        return when (intent?.action) {
            ACTION_CONNECT -> {
                restartTicks.update { it + 1 }
                START_STICKY
            }
            ACTION_STOP -> {
                shutdown()
                START_NOT_STICKY
            }
            else -> START_STICKY
        }
    }

    /** Lives while this settings snapshot is current; cancellation stops and unpublishes the link. */
    private suspend fun runLink(settings: ConnectionSettings) {
        val (transportFactory, adapterKey) = TransportFactory.create(this, settings)
        // Own scope on Default: socket I/O and parsing must not run on the main thread. The handler
        // keeps an unexpected exception in the link from taking the whole process down.
        val crashGuard = CoroutineExceptionHandler { _, e -> Log.e(TAG, "Uncaught error in link", e) }
        val linkScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashGuard)
        val link = ElmConnectionManager(
            transportFactory = transportFactory,
            adapterKey = adapterKey,
            protocolStore = appGraph.protocolStore,
            scope = linkScope,
        )
        ObdServiceState.publish(link)
        link.start()
        try {
            // Throttled: init steps can follow each other within milliseconds and the system sheds
            // notification updates above ~5/s, which could leave a stale "Initialiseren" text.
            // StateFlow is conflated, so after the pause the latest state is shown.
            link.state.collect {
                if (updateNotification(it.describe(resources))) delay(NOTIFICATION_MIN_INTERVAL_MS)
            }
        } finally {
            withContext(NonCancellable) {
                ObdServiceState.unpublish(link)
                link.stop()
                linkScope.cancel()
            }
        }
    }

    private fun shutdown() {
        lifecycleScope.launch {
            linkJob?.cancelAndJoin()
            linkJob = null
            ServiceCompat.stopForeground(this@ObdService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun enterForeground(notification: Notification): Boolean = try {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        foregroundStarted = true
        true
    } catch (e: RuntimeException) {
        Log.e(TAG, "startForeground failed", e)
        false
    }

    /** Returns whether a notification update was actually posted. */
    private fun updateNotification(text: String): Boolean {
        if (text == lastText) return false
        lastText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        return true
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ObdService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    companion object {
        private const val TAG = "ObdService"
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CONNECT = "nl.stijnvdpol.klinedash.action.CONNECT"
        private const val ACTION_STOP = "nl.stijnvdpol.klinedash.action.STOP"
        private const val NOTIFICATION_MIN_INTERVAL_MS = 500L

        /** Makes sure the service runs; an already running link is left alone. */
        fun start(context: Context) {
            startSafely(context, Intent(context, ObdService::class.java))
        }

        /** Starts the service if needed and (re)builds the link from the current settings. */
        fun connect(context: Context) {
            startSafely(context, Intent(context, ObdService::class.java).setAction(ACTION_CONNECT))
        }

        /**
         * API 31+ throws ForegroundServiceStartNotAllowedException (an IllegalStateException) when
         * the app is not considered foreground, e.g. a head unit that resumes the activity behind
         * its launcher. Not worth a crash: the user can tap Verbinden again.
         */
        private fun startSafely(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Cannot start ObdService now", e)
            }
        }
    }
}
