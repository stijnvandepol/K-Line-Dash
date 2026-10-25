package nl.stijnvdpol.klinedash.service

import kotlin.math.roundToLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.stijnvdpol.klinedash.core.geo.GeoFix

/** A fix older than this (monotonic clock) no longer describes where the car is. */
const val FIX_MAX_AGE_MS = 3_000L

/** GPS speeds are metres per second; the app shows and compares km/h. */
internal const val KMH_PER_MPS = 3.6

/** What the GPS chip on the screens shows. */
sealed interface GpsStatus {
    /** The fine location permission is missing (or only approximate location was granted). */
    data object NoPermission : GpsStatus

    /** Location is switched off in the system settings, or the device has no GPS provider. */
    data object Disabled : GpsStatus

    /** Allowed, but not listening yet: the app has not been visible since the service started. */
    data object Idle : GpsStatus

    /** Listening, no recent fix (cold start, tunnel, antenna). */
    data object Searching : GpsStatus

    /**
     * A recent fix. [rateHz] is the measured fix rate, null until enough fixes arrived to measure it.
     */
    data class Fix(val accuracyM: Double?, val satellites: Int?, val rateHz: Double?) : GpsStatus
}

/** The status for the given conditions; [latest] must be a fix that is still fresh, or null. */
internal fun gpsStatusOf(
    hasPermission: Boolean,
    isProviderEnabled: Boolean,
    isRunning: Boolean,
    latest: GeoFix?,
    rateHz: Double?,
): GpsStatus = when {
    !hasPermission -> GpsStatus.NoPermission
    !isProviderEnabled -> GpsStatus.Disabled
    !isRunning -> GpsStatus.Idle
    latest == null -> GpsStatus.Searching
    else -> GpsStatus.Fix(latest.accuracyM, latest.satellites, rateHz)
}

/**
 * The median interval of the last [WINDOW] fixes as a rate. The median ignores the odd late fix;
 * gaps above [MAX_INTERVAL_MS] (tunnel, restart) are not intervals and are skipped.
 *
 * Thread-safe: fixes are added on the main thread, the rate is read from background threads.
 */
internal class FixRateMeter {
    private val intervals = ArrayDeque<Long>()
    private var lastMs: Long? = null

    /** Number of intervals currently held, at most [WINDOW]. */
    val sampleCount: Int @Synchronized get() = intervals.size

    @Synchronized
    fun add(elapsedMs: Long) {
        val previous = lastMs
        lastMs = elapsedMs
        if (previous == null || elapsedMs <= previous || elapsedMs - previous > MAX_INTERVAL_MS) return
        intervals.addLast(elapsedMs - previous)
        if (intervals.size > WINDOW) intervals.removeFirst()
    }

    /** Fixes per second, or null until [MIN_SAMPLES] intervals were seen. */
    val rateHz: Double?
        @Synchronized get() {
            if (intervals.size < MIN_SAMPLES) return null
            val median = intervals.sorted()[intervals.size / 2]
            return MS_PER_SECOND / median
        }

    @Synchronized
    fun reset() {
        intervals.clear()
        lastMs = null
    }

    companion object {
        const val WINDOW = 10
        const val MIN_SAMPLES = 3
        private const val MAX_INTERVAL_MS = 5_000L
        private const val MS_PER_SECOND = 1000.0
    }
}

/**
 * The latest fix with the time it arrived on a monotonic clock. GPS time cannot judge freshness
 * (it is the fix's own time) and the head unit's wall clock is often wrong, so age is measured
 * against [elapsedMs] (SystemClock.elapsedRealtime in the app).
 */
internal class FixStore(private val elapsedMs: () -> Long) {
    private val _fix = MutableStateFlow<GeoFix?>(null)
    val fix: StateFlow<GeoFix?> = _fix.asStateFlow()
    private val meter = FixRateMeter()

    @Volatile
    private var receivedMs = 0L

    val rateHz: Double? get() = meter.rateHz
    val measuredIntervals: Int get() = meter.sampleCount

    /** [fixElapsedMs] is when the fix was taken on the monotonic clock; it times the rate measurement. */
    fun onFix(fix: GeoFix, fixElapsedMs: Long) {
        meter.add(fixElapsedMs)
        receivedMs = elapsedMs()
        _fix.value = fix
    }

    /** The latest fix when it arrived no more than [maxAgeMs] ago, else null. */
    fun fresh(maxAgeMs: Long): GeoFix? {
        val latest = _fix.value ?: return null
        return latest.takeIf { elapsedMs() - receivedMs <= maxAgeMs }
    }

    fun clear() {
        _fix.value = null
        meter.reset()
    }
}

/** How often the trip is sampled for a GPS that delivers [rateHz] fixes per second. */
internal fun sampleIntervalMs(rateHz: Double?): Long {
    if (rateHz == null || rateHz < MIN_FAST_RATE_HZ) return SLOW_SAMPLE_INTERVAL_MS
    return (SLOW_SAMPLE_INTERVAL_MS / rateHz).roundToLong().coerceIn(FASTEST_SAMPLE_INTERVAL_MS, SLOW_SAMPLE_INTERVAL_MS)
}

/** Below this fix rate a row per second is as good as it gets. */
private const val MIN_FAST_RATE_HZ = 1.5
private const val SLOW_SAMPLE_INTERVAL_MS = 1_000L

/** 5 Hz: more rows add file size, not information, because the OBD values update slower than that. */
private const val FASTEST_SAMPLE_INTERVAL_MS = 200L
