package nl.stijnvdpol.klinedash.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.core.geo.GeoFix

/**
 * Where positions come from: the device's GPS ([LocationTracker]), a simulated circuit
 * ([SimulatedLocationSource]) or nothing ([NoLocation]). The recorder and the lap sessions only
 * see this interface.
 */
interface LocationSource {
    /** The latest fix, null until the first one. It can be stale: use [freshFix] to decide what to record. */
    val fix: StateFlow<GeoFix?>

    val status: StateFlow<GpsStatus>

    /** True while position updates are requested. */
    val isRunning: Boolean

    /** True when this source needs the device's location service (and so the `location` foreground service type). */
    val usesDeviceGps: Boolean

    /** The latest fix when it arrived within [maxAgeMs] on a monotonic clock, else null. */
    fun freshFix(maxAgeMs: Long = FIX_MAX_AGE_MS): GeoFix?

    /** Whether [start] can work right now (for the device GPS: fine location is granted). */
    fun canStart(): Boolean

    /** Starts position updates; does nothing when they run or [canStart] is false. */
    fun start()

    fun stop()

    /** Re-reads permission and provider state into [status]; call after the user answered a permission dialog. */
    fun refresh()
}

/** No positions at all; the default where GPS is not wired (tests, OBD-only use). */
object NoLocation : LocationSource {
    override val fix: StateFlow<GeoFix?> = MutableStateFlow(null)
    override val status: StateFlow<GpsStatus> = MutableStateFlow(GpsStatus.Idle)
    override val isRunning = false
    override val usesDeviceGps = false

    override fun freshFix(maxAgeMs: Long): GeoFix? = null

    override fun canStart() = false

    override fun start() = Unit

    override fun stop() = Unit

    override fun refresh() = Unit
}

/**
 * Chooses between the device GPS and the simulated circuit. The simulation is used only when
 * [useSimulated] is true, which the app derives from "simulator transport selected" and the GPS
 * setting; with a real adapter the device GPS is always used.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationHub(
    private val device: LocationSource,
    private val simulated: LocationSource,
    private val useSimulated: StateFlow<Boolean>,
    scope: CoroutineScope,
) : LocationSource {
    private val current: LocationSource get() = if (useSimulated.value) simulated else device

    @Volatile
    private var isWanted = false

    override val fix: StateFlow<GeoFix?> =
        useSimulated.flatMapLatest { if (it) simulated.fix else device.fix }.stateIn(scope, SharingStarted.Eagerly, null)

    override val status: StateFlow<GpsStatus> =
        useSimulated.flatMapLatest { if (it) simulated.status else device.status }
            .stateIn(scope, SharingStarted.Eagerly, GpsStatus.Idle)

    override val isRunning: Boolean get() = current.isRunning
    override val usesDeviceGps: Boolean get() = current.usesDeviceGps

    init {
        // Switching the source while it runs moves the updates over, so no stale source keeps listening.
        scope.launch {
            useSimulated.collect {
                if (!isWanted) return@collect
                (if (it) device else simulated).stop()
                current.start()
            }
        }
    }

    override fun freshFix(maxAgeMs: Long): GeoFix? = current.freshFix(maxAgeMs)

    override fun canStart() = current.canStart()

    override fun start() {
        isWanted = true
        current.start()
    }

    override fun stop() {
        isWanted = false
        device.stop()
        simulated.stop()
    }

    override fun refresh() = current.refresh()
}

/** What the service does about location after the app's visibility, the permission or the recording changed. */
internal enum class GpsAction { NONE, START, STOP, STOP_AFTER_GRACE }

/**
 * Location is started when the app is visible (the only moment the `location` foreground service
 * type may be added, see ObdService) and it can run. It is stopped when it can no longer run
 * (permission taken away) and, after a grace period, when the app is hidden and no trip needs it.
 */
internal fun decideGpsAction(isAppVisible: Boolean, canStart: Boolean, isRunning: Boolean, isRecording: Boolean): GpsAction = when {
    isRunning && !canStart -> GpsAction.STOP
    isAppVisible && canStart && !isRunning -> GpsAction.START
    !isAppVisible && isRunning && !isRecording -> GpsAction.STOP_AFTER_GRACE
    else -> GpsAction.NONE
}

/** How long location keeps running after the app went to the background without a recording. */
internal const val HIDDEN_GPS_GRACE_MS = 2 * 60 * 1000L
