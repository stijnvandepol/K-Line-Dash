package nl.stijnvdpol.klinedash.service

import android.annotation.SuppressLint
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.appGraph
import nl.stijnvdpol.klinedash.core.alert.AlertEvaluator
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.elm.ElmConnectionManager
import nl.stijnvdpol.klinedash.core.obd.ObdPollScheduler
import nl.stijnvdpol.klinedash.data.ConnectionSettings
import nl.stijnvdpol.klinedash.data.StartupSettings

/**
 * Foreground service that owns the one [ElmConnectionManager] and the [ObdPollScheduler] on top of
 * it (same scope, same lifetime). It is independent of the UI: the
 * activity may be killed and recreated while the link stays up; the UI finds the link through
 * [ObdServiceState].
 *
 * GPS: the service starts (also at boot) as a `connectedDevice` foreground service only. When the UI
 * reports a visible activity ([ObdServiceState.setAppVisible]) and the fine location permission is
 * granted, it calls startForeground again with `connectedDevice|location` and starts the location
 * source; if that throws it logs and carries on without GPS (trips record from OBD alone). See
 * [superviseLocation] and docs/research/gps-laps-export.md section 1.4.
 *
 * No partial wake lock (so none is declared in the manifest): the foreground service keeps the
 * process alive and the link is idle-light; a head unit that sleeps the CPU also powers down the ELM
 * adapter's USB/BT rail in practice, and ACC off suspends the link anyway (see [onWakeSignal]).
 */
class ObdService : LifecycleService() {

    /** Bumped by ACTION_CONNECT to rebuild the link even though the settings did not change. */
    private val restartTicks = MutableStateFlow(0)

    /** True while the link is closed because the car went off (ACC-off broadcast); the service stays. */
    private val linkSuspended = MutableStateFlow(false)
    private var wakeSignals: WakeSignalReceivers? = null

    @Volatile
    private var startupSettings = StartupSettings()
    private var linkJob: Job? = null
    private var recorderJob: Job? = null
    private var foregroundStarted = false

    // Starts as connectedDevice only (boot start); superviseLocation adds the location type later.
    @SuppressLint("InlinedApi")
    private var foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
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
        // Default dispatcher: the loop writes files once a second. It finishes the running trip when cancelled.
        recorderJob = lifecycleScope.launch(Dispatchers.Default) { appGraph.tripRecorder.run() }
        linkJob = lifecycleScope.launch {
            // Only a different adapter rebuilds the link: toggling autoConnect, the stored device name
            // (written as a separate row right after the address) or another kind's fields must not
            // drop a running connection or abort one that is being set up.
            val settings = appGraph.settingsRepository.connectionSettings
                .distinctUntilChangedBy { TransportFactory.adapterKey(it) }
            // Suspended: no settings, so the running link is cancelled (and closed) and none is built.
            combine(settings, restartTicks, linkSuspended) { s, _, isSuspended -> s.takeUnless { isSuspended } }
                .collectLatest { if (it != null) runLink(it) }
        }
        wakeSignals = WakeSignalReceivers(this, ::onWakeSignal)
        lifecycleScope.launch { appGraph.startupSettingsRepository.settings.collect(::applyStartupSettings) }
        lifecycleScope.launch { superviseLocation() }
        lifecycleScope.launch(Dispatchers.Default) { appGraph.lapSessions.keepSectorsOrdered() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!foregroundStarted) return START_NOT_STICKY
        // startForegroundService() obliges every start to end in startForeground(), also when already running.
        enterForeground(buildNotification(lastText))
        return when (intent?.action) {
            ACTION_CONNECT -> {
                rebuildLink()
                START_STICKY
            }
            ACTION_STOP -> {
                shutdown()
                START_NOT_STICKY
            }
            else -> {
                // Plain start (the app was opened): someone is here, so a link closed for ACC off returns.
                if (linkSuspended.value) rebuildLink()
                START_STICKY
            }
        }
    }

    /** Lives while this settings snapshot is current; cancellation stops and unpublishes the link. */
    private suspend fun runLink(settings: ConnectionSettings) {
        val (transportFactory, adapterKey) = TransportFactory.create(this, settings)
        // Own scope on Default: socket I/O and parsing must not run on the main thread. The handler
        // keeps an unexpected exception in the link from taking the whole process down.
        val crashGuard = CoroutineExceptionHandler { _, e -> Log.e(TAG, "Uncaught error in link", e) }
        val linkScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashGuard)
        // The make orders the protocol fallback; read at every attempt, so a make chosen or detected
        // while the link retries counts on the next attempt. Null until the settings are read.
        val make = appGraph.carSettingsRepository.settings.map { it.effectiveMake }
            .stateIn(linkScope, SharingStarted.Eagerly, null)
        val link = ElmConnectionManager(
            transportFactory = transportFactory,
            adapterKey = adapterKey,
            protocolStore = appGraph.protocolStore,
            scope = linkScope,
            makeHint = { make.value },
        )
        val poller = ObdPollScheduler(link, linkScope)
        ObdServiceState.publish(link, poller)
        link.start()
        // Waits for the link to be Connected; with nothing demanded it only detects the supported PIDs.
        poller.start()
        linkScope.launch { appGraph.vinDetector.run(adapterKey, link, poller) }
        val alertMonitor = AlertMonitor(
            settings = appGraph.alertSettingsRepository.settings,
            rules = AlertEvaluator,
            tonePlayer = ToneGeneratorPlayer(),
            publish = ObdServiceState::publishAlerts,
        )
        linkScope.launch { alertMonitor.run(poller, link.state) }
        try {
            // Throttled: init steps can follow each other within milliseconds and the system sheds
            // notification updates above ~5/s, which could leave a stale "Initializing" text.
            // StateFlow is conflated, so after the pause the latest state is shown.
            link.state.collect {
                if (updateNotification(it.describe(resources))) delay(NOTIFICATION_MIN_INTERVAL_MS)
            }
        } finally {
            withContext(NonCancellable) {
                ObdServiceState.unpublish(link)
                poller.stop()
                link.stop()
                linkScope.cancel()
            }
        }
    }

    /** The user's settings changed (or arrived): listen for the listed vendor broadcasts. */
    private fun applyStartupSettings(settings: StartupSettings) {
        startupSettings = settings
        wakeSignals?.setVendorActions(settings.allActions)
        // Without wake handling nothing would ever end a suspension, so it ends with the option.
        if (!settings.reconnectOnWake && linkSuspended.value) rebuildLink()
    }

    private fun onWakeSignal(action: String) {
        val settings = startupSettings
        val event = classifyBroadcast(action, settings) ?: return
        if (event == PowerEvent.ACC_ON || event == PowerEvent.ACC_OFF) {
            // This is how the user learns which action their unit sends.
            lifecycleScope.launch { appGraph.startupSettingsRepository.recordReceived(action, System.currentTimeMillis()) }
        }
        // Whatever happens to the link: the drive is over, and the unit may kill the app any second now.
        if (event == PowerEvent.ACC_OFF) lifecycleScope.launch { appGraph.tripRecorder.finishForIgnitionOff() }
        val state = ObdServiceState.link.value?.state?.value ?: ConnectionState.Idle
        val decision = decideWakeAction(event, linkPhaseOf(state, linkSuspended.value), settings.reconnectOnWake)
        Log.i(TAG, "Signal $action ($event) while ${state::class.simpleName}: $decision")
        when (decision) {
            WakeAction.NONE -> Unit
            WakeAction.RECONNECT -> rebuildLink()
            WakeAction.SUSPEND -> {
                linkSuspended.value = true
                updateNotification(getString(R.string.state_suspended))
            }
        }
    }

    /** Builds the link anew, which skips any backoff wait; ends a suspension (one rebuild, not two). */
    private fun rebuildLink() {
        if (linkSuspended.value) linkSuspended.value = false else restartTicks.update { it + 1 }
    }

    override fun onDestroy() {
        wakeSignals?.close()
        wakeSignals = null
        appGraph.location.stop()
        appGraph.exportController.stop()
        super.onDestroy()
    }

    private fun shutdown() {
        lifecycleScope.launch {
            linkJob?.cancelAndJoin()
            linkJob = null
            // Joined so the running trip is finished in the database before the process may go.
            recorderJob?.cancelAndJoin()
            recorderJob = null
            ServiceCompat.stopForeground(this@ObdService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // The connectedDevice type constant is API 29; ServiceCompat ignores the type below that, so
    // inlining it on API 26-28 is harmless.
    @SuppressLint("InlinedApi")
    private fun enterForeground(notification: Notification): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
        foregroundStarted = true
        true
    } catch (e: RuntimeException) {
        Log.e(TAG, "startForeground failed", e)
        false
    }

    /**
     * Follows the app's visibility, the location permission and the recording and starts or stops
     * the location source accordingly (see [decideGpsAction]). The service itself starts at boot as
     * `connectedDevice` only: the `location` type may not be added from the background, and a boot
     * broadcast is not a foreground state. So GPS waits until the activity is visible.
     */
    private suspend fun superviseLocation() {
        val location = appGraph.location
        val isRecording = appGraph.tripRecorder.state.map { it is RecordingState.Recording }.distinctUntilChanged()
        combine(
            ObdServiceState.isAppVisible, ObdServiceState.locationPermissionChanges, isRecording, appGraph.useSimulatedGps,
        ) { isVisible, _, recording, _ -> isVisible to recording }
            .collectLatest { (isVisible, recording) ->
                location.refresh()
                // The hub may have moved running updates over to the device GPS (simulator switched off).
                if (isVisible && location.isRunning && location.usesDeviceGps) addLocationForegroundType()
                when (decideGpsAction(isVisible, location.canStart(), location.isRunning, recording)) {
                    GpsAction.START -> startLocation(location)
                    GpsAction.STOP -> location.stop()
                    GpsAction.STOP_AFTER_GRACE -> {
                        delay(HIDDEN_GPS_GRACE_MS)
                        location.stop()
                    }
                    GpsAction.NONE -> Unit
                }
            }
    }

    /** The device GPS only starts once the foreground service type allows it; the simulated source needs no type. */
    private fun startLocation(location: LocationSource) {
        if (location.usesDeviceGps && !addLocationForegroundType()) return
        location.start()
    }

    /**
     * Calls startForeground again with `connectedDevice|location`. Only works while the app is
     * visible (targetSdk 34 checks the while-in-use permission when the type is added), which is why
     * [startLocation] runs from the visibility signal. When it throws, the service carries on without
     * GPS: trips still record from OBD alone. The new type is kept for later startForeground calls,
     * otherwise the next start command would drop it again.
     */
    @SuppressLint("InlinedApi")
    private fun addLocationForegroundType(): Boolean {
        val withLocation = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (foregroundType == withLocation) return true
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(lastText), withLocation)
            foregroundType = withLocation
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "Cannot add the location foreground service type, continuing without GPS", e)
            false
        }
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
            this, REQUEST_OPEN_APP,
            dashboardIntent(this).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, REQUEST_STOP_SERVICE,
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
        private const val REQUEST_OPEN_APP = 0
        private const val REQUEST_STOP_SERVICE = 1

        /** Makes sure the service runs; an already running link is left alone. */
        fun start(context: Context) {
            startSafely(context, Intent(context, ObdService::class.java))
        }

        /** Starts the service if needed and (re)builds the link from the current settings. */
        fun connect(context: Context) {
            startSafely(context, Intent(context, ObdService::class.java).setAction(ACTION_CONNECT))
        }

        /**
         * The app's launcher activity (the dashboard). Resolved through the package manager, so the
         * service layer does not depend on the UI classes; the manifest always declares it.
         */
        internal fun dashboardIntent(context: Context): Intent =
            checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)) {
                "The manifest declares no launcher activity"
            }

        /**
         * API 31+ throws ForegroundServiceStartNotAllowedException (an IllegalStateException) when
         * the app is not considered foreground, e.g. a head unit that resumes the activity behind
         * its launcher. Not worth a crash: the user can tap Connect again.
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
