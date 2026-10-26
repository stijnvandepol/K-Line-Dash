package nl.stijnvdpol.klinedash.service

import android.database.SQLException
import android.os.SystemClock
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.obd.ObdPoller
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.obd.SignalIds
import nl.stijnvdpol.klinedash.data.Track
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.data.TripDao
import nl.stijnvdpol.klinedash.data.TripSettings
import nl.stijnvdpol.klinedash.data.updateSummary

private const val TAG = "TripRecorder"
private const val DEMAND_OWNER = "trip"
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

    /** There is no adapter link and no GPS fix yet, so there would be nothing to record. */
    data object NotConnected : StartResult
    data class Failed(val reason: String) : StartResult
}

/**
 * Records trips to CSV files, either started by the user ([start]/[stop]) or automatically
 * (engine running, or moving on a phone, per [TripSettings], see [AutoRecordPolicy]).
 *
 * [run] has to be running (the service launches it) for samples to be taken: the readings of the
 * current poller and the latest GPS fix go into a [TripSession], and the trip row in Room is
 * finished with the summary on [stop]. A trip that is never finished (ACC power cut) is repaired by
 * [recoverUnfinished] at the next app start.
 *
 * Sample rate: once a second, or the GPS fix rate when the GPS delivers faster, at most 5 Hz (see
 * [sampleIntervalMs]; the OBD values update slower than that, so more rows add size, not detail).
 * Each row gets the latest fix only if it arrived within [FIX_MAX_AGE_MS]; otherwise the GPS cells
 * are empty, so a trip started before the first fix or in a tunnel still records. The row time is
 * the wall clock; when the unit's clock is behind GPS time the row's `gps_age` is clamped to 0, which
 * only costs the lap times a few tens of milliseconds of tick jitter. Everything that measures an
 * interval (the settle time of the CSV header, the automatic stop's grace, the motion detector) runs
 * on the monotonic [elapsed] clock instead: a head unit without a clock chip steps its wall clock at
 * boot, by hours, and the wall clock is only for what is shown and recorded.
 *
 * A trip can run without an adapter link when a GPS fix exists (phone use): the OBD columns are then
 * empty. With [TripSettings.autoStartWhenMoving] such a trip also starts by itself (see [MotionDetector]).
 *
 * Lap timing: the track in [activeTrack] when the trip starts, and any track set while it runs, is
 * stored with the trip and timed live (see [live]). Lap timing covers the lap session only: a track
 * set mid-trip stores, next to the track, the time it started ([Trip.lapTimingAfterMs]), so the
 * analysis of the finished file times the same part of the trip and finds the same laps as the live
 * timer (a pass of the line before the session is no lap; the pass at which the line was set starts
 * lap 1). Ending the lap session (null track) does not take the laps away from the trip that is
 * running. A lap session ends with its trip, however the
 * trip ends (stop, ignition off, the engine stopping, a write error, the service stopping): the
 * recorder then hands the trip's track to [releaseTrack], so the next trip is a plain one whether or
 * not any screen is there to see it.
 *
 * A trip with a track gets its cached lap count and best lap recomputed from the finished CSV by
 * [refreshSummary]: a start/finish line set mid-trip restarts the live lap timer (an added sector
 * line does not), and the file is what a later track change recomputes.
 * When the location source changes during a trip (simulated circuit on or off), the GPS distance
 * starts a new segment, so the jump between the two sources is not driven distance.
 *
 * Retention: when a trip is finished and once after [recoverUnfinished], [pruneTrips] gets the
 * [TripSettings.keepTrips] limit and removes the oldest finished trips beyond it (see [TripLibrary.prune]).
 *
 * Demand on the poller, owner "trip": while recording [SUMMARY_SIGNAL_IDS] (rpm, speed, coolant, air
 * flow and throttle, so the summary, the fuel estimate and the full-throttle time mean something);
 * otherwise only rpm when auto-record is on (to notice the engine starting). Everything else in the
 * CSV is whatever the screens and warnings poll anyway.
 *
 * All state changes happen under one lock, so UI calls and the sampling loop cannot interleave.
 */
class TripRecorder internal constructor(
    private val tripsDir: File,
    private val tripDao: TripDao,
    private val settings: Flow<TripSettings>,
    private val poller: StateFlow<ObdPoller?>,
    private val clock: () -> Long,
    private val elapsed: () -> Long,
    private val zone: ZoneId,
    private val ioDispatcher: CoroutineDispatcher,
    private val location: LocationSource = NoLocation,
    private val activeTrack: Flow<Track?> = flowOf(null),
    private val refreshSummary: suspend (tripId: Long) -> Unit = {},
    private val releaseTrack: suspend (trackId: Long) -> Unit = {},
    private val pruneTrips: suspend (keep: Int) -> Unit = {},
) : LiveLaps {
    constructor(
        tripsDir: File,
        tripDao: TripDao,
        settings: Flow<TripSettings>,
        poller: StateFlow<ObdPoller?>,
        location: LocationSource,
        activeTrack: Flow<Track?>,
        refreshSummary: suspend (tripId: Long) -> Unit,
        releaseTrack: suspend (trackId: Long) -> Unit,
        pruneTrips: suspend (keep: Int) -> Unit,
    ) : this(
        tripsDir, tripDao, settings, poller, System::currentTimeMillis, SystemClock::elapsedRealtime, ZoneId.systemDefault(),
        Dispatchers.IO,
        location, activeTrack, refreshSummary, releaseTrack, pruneTrips,
    )

    private class Active(val tripId: Long, val session: TripSession, val startMs: Long, val origin: RecordingOrigin)

    private val lock = Mutex()
    private val policy = AutoRecordPolicy()
    private val recovery = TripRecovery(tripsDir, tripDao, ioDispatcher, refreshSummary, releaseTrack)
    private val motion = MotionDetector()
    private var active: Active? = null
    private var latestTrack: Track? = null
    private var demandedOn: ObdPoller? = null
    private var demandedIds: Set<String> = emptySet()

    /** [LocationSource.usesDeviceGps] at the previous sample: tells the device GPS from the simulated circuit. */
    private var sampledFromDeviceGps: Boolean? = null

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _live = MutableStateFlow(LiveTripState.Idle)

    /** Statistics, lap timer and laps of the trip being recorded, updated with every sample; [LiveTripState.Idle] when idle. */
    override val live: StateFlow<LiveTripState> = _live.asStateFlow()

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

    override suspend fun currentLapFixes(): List<GeoFix> = lock.withLock { active?.session?.currentLapFixes().orEmpty() }

    /**
     * See [TripRecovery]; then the trips beyond the retention limit are removed (once per app start,
     * outside the lock: a first run after an update may have many files to delete, and starting a trip
     * must not wait for that). Safe to call while recording: the active trip is left alone.
     */
    suspend fun recoverUnfinished() {
        lock.withLock { recovery.recover(active?.tripId) }
        removeTripsBeyondLimit()
    }

    /**
     * The sampling loop; never returns. On cancellation (service stopped) the running trip is
     * finished first, so a normal shutdown leaves no unfinished row. An unexpected exception in a
     * tick or in following the settings or the active track is logged and the loop goes on, so a
     * bug there costs a sample or a setting update, not the trip.
     */
    suspend fun run() = coroutineScope {
        val currentSettings = MutableStateFlow(settings.first())
        launch { survivingErrors(TAG, "Following the trip settings") { settings.collect { currentSettings.value = it } } }
        launch {
            survivingErrors(TAG, "Following the active track") {
                activeTrack.collect { track ->
                    survivingErrors(TAG, "Applying the active track") { lock.withLock { applyTrackLocked(track) } }
                }
            }
        }
        try {
            while (true) {
                survivingErrors(TAG, "Taking a trip sample") { tick(currentSettings.value) }
                delay(sampleIntervalMs((location.status.value as? GpsStatus.Fix)?.rateHz))
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

    private suspend fun tick(options: TripSettings) = lock.withLock {
        val isAutoRecordEnabled = options.autoRecord
        val currentPoller = poller.value
        val readings = currentPoller?.readings?.value.orEmpty()
        val nowMs = clock()
        val elapsedMs = elapsed()
        val gps = location.freshFix(FIX_MAX_AGE_MS)
        updateDemand(currentPoller, isAutoRecordEnabled)
        val isEngineRunning = currentPoller != null && isEngineRunning(readings, nowMs)
        val gpsSpeedKmh = gps?.speedMps?.times(KMH_PER_MPS)?.takeIf { options.autoStartWhenMoving }
        val isDriving = motion.update(elapsedMs, gpsSpeedKmh) || isEngineRunning
        when (policy.decide(elapsedMs, isDriving, isAutoRecordEnabled, active?.origin)) {
            AutoRecordAction.START -> if (startLocked(RecordingOrigin.AUTO) is StartResult.Failed) {
                policy.suppressStartUntilEngineStops()
            }
            AutoRecordAction.STOP -> finishLocked()
            AutoRecordAction.NONE -> Unit
        }
        if (currentPoller != null || gps != null) sample(nowMs, readings, gps)
        updateDemand(currentPoller, isAutoRecordEnabled)
    }

    private fun updateDemand(currentPoller: ObdPoller?, isAutoRecordEnabled: Boolean) {
        val wanted = when {
            active != null -> SUMMARY_SIGNAL_IDS.toSet()
            isAutoRecordEnabled -> setOf(SignalIds.RPM)
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
        if (poller.value == null && location.freshFix(FIX_MAX_AGE_MS) == null) return StartResult.NotConnected
        val nowMs = clock()
        return try {
            val session = withContext(ioDispatcher) { createSession(nowMs) }
            val track = latestTrack
            session.setTrack(track?.id, track?.lapGates())
            val tripId = insertTrip(session, nowMs, track?.id)
            active = Active(tripId, session, nowMs, origin)
            _live.value = session.liveState()
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
            if (file.createNewFile()) return TripSession(file, elapsed)
        }
        throw IOException("No free trip file name for $startMs")
    }

    private suspend fun insertTrip(session: TripSession, startMs: Long, trackId: Long?): Long = try {
        tripDao.insert(
            Trip(
                startMs = startMs, endMs = null, fileName = session.file.name, sampleCount = 0,
                distanceKm = 0.0, maxRpm = null, maxSpeedKmh = null, maxCoolantC = null, trackId = trackId,
            ),
        )
    } catch (e: Exception) {
        // No row means nothing would ever finish or list this file: do not leave it behind. Any
        // exception counts, also a bug or a cancellation, or the file would stay open for good.
        withContext(NonCancellable + ioDispatcher) {
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

    /** A new active track applies to the running trip from now on; no track leaves the running trip as it is. */
    private suspend fun applyTrackLocked(track: Track?) {
        latestTrack = track
        val current = active ?: return
        track ?: return
        val gates = track.lapGates()
        if (current.session.trackId == track.id && current.session.gates == gates) return
        current.session.setTrack(track.id, gates)
        _live.value = current.session.liveState()
        try {
            tripDao.setTrack(current.tripId, track.id, current.session.lapTimingAfterMs)
        } catch (e: SQLException) {
            Log.e(TAG, "Could not store the track of trip ${current.tripId}", e)
        }
    }

    private suspend fun sample(nowMs: Long, readings: Map<String, Reading>, gps: GeoFix?) {
        val current = active ?: return
        val fromDeviceGps = location.usesDeviceGps
        // The other source's positions are elsewhere (and its clock may differ): never join them into distance.
        if (sampledFromDeviceGps != null && sampledFromDeviceGps != fromDeviceGps) current.session.startNewGpsSegment()
        sampledFromDeviceGps = fromDeviceGps
        try {
            withContext(ioDispatcher) { current.session.add(nowMs, readings, gps) }
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
        _live.value = current.session.liveState()
    }

    /**
     * Closes the file and stores the summary; a trip without any sample is deleted again. When the
     * row cannot be written (storage full) it stays unfinished and [recoverUnfinished] repairs it
     * from the file at the next app start; the recording itself is over either way.
     */
    private suspend fun finishLocked() {
        val finished = active ?: return
        active = null
        sampledFromDeviceGps = null
        _state.value = RecordingState.Idle
        _live.value = LiveTripState.Idle
        withContext(NonCancellable + ioDispatcher) {
            try {
                finished.session.close()
            } catch (e: IOException) {
                Log.e(TAG, "Closing the trip file failed", e)
                _lastError.value = "The last seconds of the trip may be missing from the file."
            }
            try {
                storeFinished(finished)
            } catch (e: SQLException) {
                Log.e(TAG, "Could not store the end of trip ${finished.tripId}, left for recovery", e)
                _lastError.value = "The end of the trip could not be saved. Check the free storage of the head unit."
            }
            finished.session.trackId?.let { releaseFinishedTrack(it) }
            removeTripsBeyondLimit()
        }
    }

    /** Retention: the trips beyond [TripSettings.keepTrips] go; a failure costs a tidy-up, never the recording. */
    private suspend fun removeTripsBeyondLimit() {
        survivingErrors(TAG, "Removing the trips beyond the limit") { pruneTrips(settings.first().keepTrips) }
    }

    private suspend fun storeFinished(finished: Active) {
        val summary = finished.session.summary()
        if (summary == null) {
            finished.session.file.delete()
            tripDao.deleteById(finished.tripId)
            return
        }
        tripDao.finish(
            id = finished.tripId,
            endMs = summary.endMs,
            sampleCount = summary.sampleCount,
            distanceKm = summary.distanceKm,
            maxRpm = summary.maxRpm,
            maxSpeedKmh = summary.maxSpeedKmh,
            maxCoolantC = summary.maxCoolantC,
        )
        tripDao.updateSummary(finished.tripId, finished.session.cache())
        if (finished.session.trackId != null) refreshLapSummary(finished.tripId, refreshSummary)
    }

    /** The lap session is over: the next trip is not timed on [trackId] unless the user chooses it again. */
    private suspend fun releaseFinishedTrack(trackId: Long) {
        // The settings flow tells the recorder the same a moment later; the next trip must not wait for that.
        if (latestTrack?.id == trackId) latestTrack = null
        releaseActiveTrack(trackId, releaseTrack)
    }
}
