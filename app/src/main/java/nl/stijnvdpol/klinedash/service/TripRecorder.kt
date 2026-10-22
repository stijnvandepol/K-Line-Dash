package nl.stijnvdpol.klinedash.service

import android.database.SQLException
import android.util.Log
import java.io.File
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.stijnvdpol.klinedash.core.obd.ObdPoller
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.data.TripDao
import nl.stijnvdpol.klinedash.data.TripSettings

private const val TAG = "TripRecorder"
private const val DEMAND_OWNER = "trip"
private const val RPM_ID = "rpm"
private const val SAMPLE_INTERVAL_MS = 1_000L
private const val MAX_FILE_NAME_ATTEMPTS = 100

/** What the recorder is doing; shown by the UI as a recording indicator. */
sealed interface RecordingState {
    data object Idle : RecordingState

    /** [samples] counts the rows taken so far; [isAuto] is true when the engine started it. */
    data class Recording(
        val startMs: Long,
        val samples: Int,
        val fileName: String,
        val isAuto: Boolean,
    ) : RecordingState
}

/** Result of [TripRecorder.start]; [Failed.reason] is a sentence for the user. */
sealed interface StartResult {
    data object Started : StartResult
    data object AlreadyRecording : StartResult

    /** There is no adapter link yet, so there would be nothing to record. */
    data object NotConnected : StartResult
    data class Failed(val reason: String) : StartResult
}

/**
 * Records trips to CSV files, either started by the user ([start]/[stop]) or automatically
 * (engine running, per [TripSettings.autoRecord], see [AutoRecordPolicy]).
 *
 * [run] has to be running (the service launches it) for samples to be taken: once a second the
 * readings of the current poller go into a [TripSession], and the trip row in Room is finished with
 * the summary on [stop]. A trip that is never finished (ACC power cut) is repaired by
 * [recoverUnfinished] at the next app start.
 *
 * Demand on the poller, owner "trip": while recording rpm, speed and coolant (so the summary
 * means something); otherwise only rpm when auto-record is on (to notice the engine starting).
 * Everything else in the CSV is whatever the screens and warnings poll anyway.
 *
 * All state changes happen under one lock, so UI calls and the sampling loop cannot interleave.
 */
class TripRecorder internal constructor(
    private val tripsDir: File,
    private val tripDao: TripDao,
    private val settings: Flow<TripSettings>,
    private val poller: StateFlow<ObdPoller?>,
    private val clock: () -> Long,
    private val zone: ZoneId,
    private val ioDispatcher: CoroutineDispatcher,
) {
    constructor(tripsDir: File, tripDao: TripDao, settings: Flow<TripSettings>, poller: StateFlow<ObdPoller?>) :
        this(tripsDir, tripDao, settings, poller, System::currentTimeMillis, ZoneId.systemDefault(), Dispatchers.IO)

    private class Active(val tripId: Long, val session: TripSession, val startMs: Long, val origin: RecordingOrigin)

    private val lock = Mutex()
    private val policy = AutoRecordPolicy()
    private val recovery = TripRecovery(tripsDir, tripDao, ioDispatcher)
    private var active: Active? = null
    private var demandedOn: ObdPoller? = null
    private var demandedIds: Set<String> = emptySet()

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    /** Why the last start or recording failed (a sentence for the user); cleared by the next successful start. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Starts a manual recording; it runs until [stop] or until the service stops. */
    suspend fun start(): StartResult = lock.withLock { startLocked(RecordingOrigin.MANUAL) }

    /** Ends the current recording (manual or automatic); no automatic one follows until the engine has stopped once. */
    suspend fun stop() = lock.withLock {
        if (active != null) {
            policy.suppressStartUntilEngineStops()
            finishLocked()
        }
    }

    /**
     * The ignition went off (the head unit's ACC-off broadcast): the running trip, manual or
     * automatic, is finished now. The unit may cut power or stop the app within seconds, long before
     * the automatic stop's grace period is over, which would leave the trip unfinished. No automatic
     * trip starts until the engine has been seen off, so the last fresh rpm reading of the drive
     * that just ended cannot start a new one; the next drive records as usual.
     */
    suspend fun finishForIgnitionOff() = lock.withLock {
        policy.suppressStartUntilEngineStops()
        finishLocked()
    }

    /** See [TripRecovery]. Safe to call while recording: the active trip is left alone. */
    suspend fun recoverUnfinished() = lock.withLock { recovery.recover(active?.tripId) }

    /**
     * The sampling loop; never returns. On cancellation (service stopped) the running trip is
     * finished first, so a normal shutdown leaves no unfinished row.
     */
    suspend fun run() = coroutineScope {
        val isAutoRecordEnabled = MutableStateFlow(settings.first().autoRecord)
        launch { settings.collect { isAutoRecordEnabled.value = it.autoRecord } }
        try {
            while (true) {
                tick(isAutoRecordEnabled.value)
                delay(SAMPLE_INTERVAL_MS)
            }
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    finishLocked()
                    demandedOn?.release(DEMAND_OWNER)
                    demandedOn = null
                    demandedIds = emptySet()
                }
            }
        }
    }

    private suspend fun tick(isAutoRecordEnabled: Boolean) = lock.withLock {
        val currentPoller = poller.value
        val readings = currentPoller?.readings?.value.orEmpty()
        val nowMs = clock()
        updateDemand(currentPoller, isAutoRecordEnabled)
        val isEngineRunning = currentPoller != null && isEngineRunning(readings, nowMs)
        when (policy.decide(nowMs, isEngineRunning, isAutoRecordEnabled, active?.origin)) {
            AutoRecordAction.START -> if (startLocked(RecordingOrigin.AUTO) is StartResult.Failed) {
                policy.suppressStartUntilEngineStops()
            }
            AutoRecordAction.STOP -> finishLocked()
            AutoRecordAction.NONE -> Unit
        }
        if (currentPoller != null) sample(nowMs, readings)
        updateDemand(currentPoller, isAutoRecordEnabled)
    }

    private fun updateDemand(currentPoller: ObdPoller?, isAutoRecordEnabled: Boolean) {
        val wanted = when {
            active != null -> SUMMARY_SIGNAL_IDS.toSet()
            isAutoRecordEnabled -> setOf(RPM_ID)
            else -> emptySet()
        }
        if (currentPoller === demandedOn && wanted == demandedIds) return
        // A replaced poller is discarded together with its link scope; there is nothing to release on it.
        if (wanted.isEmpty()) currentPoller?.release(DEMAND_OWNER) else currentPoller?.demand(DEMAND_OWNER, wanted)
        demandedOn = currentPoller
        demandedIds = wanted
    }

    private suspend fun startLocked(origin: RecordingOrigin): StartResult {
        if (active != null) return StartResult.AlreadyRecording
        if (poller.value == null) return StartResult.NotConnected
        val nowMs = clock()
        return try {
            val session = withContext(ioDispatcher) { createSession(nowMs) }
            val tripId = insertTrip(session, nowMs)
            active = Active(tripId, session, nowMs, origin)
            _lastError.value = null
            _state.value = RecordingState.Recording(nowMs, 0, session.file.name, origin == RecordingOrigin.AUTO)
            StartResult.Started
        } catch (e: IOException) {
            failStart("Could not create the trip file. Check the free storage of the head unit.", e)
        } catch (e: SQLException) {
            failStart("Could not save the trip. Check the free storage of the head unit.", e)
        }
    }

    /** The file is claimed with createNewFile, so two trips in the same second get distinct names. */
    private fun createSession(startMs: Long): TripSession {
        if (!tripsDir.isDirectory && !tripsDir.mkdirs()) throw IOException("Cannot create $tripsDir")
        for (index in 1..MAX_FILE_NAME_ATTEMPTS) {
            val file = File(tripsDir, tripFileName(startMs, zone, index))
            if (file.createNewFile()) return TripSession(file, startMs)
        }
        throw IOException("No free trip file name for $startMs")
    }

    private suspend fun insertTrip(session: TripSession, startMs: Long): Long = try {
        tripDao.insert(
            Trip(
                startMs = startMs, endMs = null, fileName = session.file.name, sampleCount = 0,
                distanceKm = 0.0, maxRpm = null, maxSpeedKmh = null, maxCoolantC = null,
            ),
        )
    } catch (e: SQLException) {
        // No row means nothing would ever finish or list this file: do not leave it behind.
        withContext(ioDispatcher) {
            session.close()
            session.file.delete()
        }
        throw e
    }

    private fun failStart(reason: String, cause: Exception): StartResult.Failed {
        Log.e(TAG, "Could not start a trip", cause)
        _lastError.value = reason
        return StartResult.Failed(reason)
    }

    private suspend fun sample(nowMs: Long, readings: Map<String, Reading>) {
        val current = active ?: return
        try {
            withContext(ioDispatcher) { current.session.add(nowMs, readings) }
        } catch (e: IOException) {
            Log.e(TAG, "Writing the trip file failed, ending the trip", e)
            _lastError.value = "The trip stopped because the file could not be written. Check the free storage of the head unit."
            policy.suppressStartUntilEngineStops()
            finishLocked()
            return
        }
        _state.value = RecordingState.Recording(
            current.startMs, current.session.sampleCount, current.session.file.name, current.origin == RecordingOrigin.AUTO,
        )
    }

    /** Closes the file and stores the summary; a trip without any sample is deleted again. */
    private suspend fun finishLocked() {
        val finished = active ?: return
        active = null
        _state.value = RecordingState.Idle
        withContext(NonCancellable + ioDispatcher) {
            try {
                finished.session.close()
            } catch (e: IOException) {
                Log.e(TAG, "Closing the trip file failed", e)
                _lastError.value = "The last seconds of the trip may be missing from the file."
            }
            val summary = finished.session.summary()
            if (summary == null) {
                finished.session.file.delete()
                tripDao.deleteById(finished.tripId)
            } else {
                tripDao.finish(
                    id = finished.tripId,
                    endMs = summary.endMs,
                    sampleCount = summary.sampleCount,
                    distanceKm = summary.distanceKm,
                    maxRpm = summary.maxRpm,
                    maxSpeedKmh = summary.maxSpeedKmh,
                    maxCoolantC = summary.maxCoolantC,
                )
            }
        }
    }
}
