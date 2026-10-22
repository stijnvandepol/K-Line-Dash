package nl.stijnvdpol.klinedash.service

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.geo.Geodesy
import nl.stijnvdpol.klinedash.core.geo.LatLon
import nl.stijnvdpol.klinedash.core.obd.ObdPoller

/**
 * A stadium-shaped circuit: two straights of [straightM] joined by two half circles, driven
 * counter-clockwise. The start/finish line is at distance 0, at the start of the east-side straight,
 * heading north. The default is 2 km long (2 x 500 m straights, 2 x 500 m half circles).
 *
 * Pure geometry on a flat local frame around [origin] (the same projection the lap detector uses).
 */
class SimulatedCircuit(
    private val origin: LatLon = DEFAULT_ORIGIN,
    private val straightM: Double = 500.0,
    private val radiusM: Double = straightM / PI,
) {
    /** One lap in metres. */
    val lengthM: Double = 2 * straightM + 2 * PI * radiusM

    /** Position and direction of travel [distanceM] metres after the start/finish line (any value, wraps around). */
    fun pointAt(distanceM: Double): Pair<LatLon, Double> {
        val s = ((distanceM % lengthM) + lengthM) % lengthM
        val halfCircleM = PI * radiusM
        val (x, y, headingDeg) = when {
            s < straightM -> Triple(radiusM, s, NORTH_DEG)
            s < straightM + halfCircleM -> arc(centerY = straightM, angle = (s - straightM) / radiusM)
            s < 2 * straightM + halfCircleM -> Triple(-radiusM, straightM - (s - straightM - halfCircleM), SOUTH_DEG)
            else -> arc(centerY = 0.0, angle = PI + (s - 2 * straightM - halfCircleM) / radiusM)
        }
        return toLatLon(x, y) to headingDeg
    }

    /** Counter-clockwise arc around (0, [centerY]) at [angle] radians; the heading is the tangent. */
    private fun arc(centerY: Double, angle: Double): Triple<Double, Double, Double> {
        val heading = Math.toDegrees(atan2(-sin(angle), cos(angle)))
        return Triple(radiusM * cos(angle), centerY + radiusM * sin(angle), Geodesy.normalizeDegrees(heading))
    }

    private fun toLatLon(eastM: Double, northM: Double): LatLon {
        val metresPerDegree = Math.toRadians(1.0) * Geodesy.EARTH_RADIUS_M
        val lon = origin.lon + eastM / (metresPerDegree * cos(Math.toRadians(origin.lat)))
        return LatLon(origin.lat + northM / metresPerDegree, lon)
    }

    companion object {
        /** An arbitrary open spot, not a real address. */
        val DEFAULT_ORIGIN = LatLon(51.0, 5.0)
        private const val NORTH_DEG = 0.0
        private const val SOUTH_DEG = 180.0
    }
}

private const val SIMULATED_FIX_INTERVAL_MS = 200L
private const val SPEED_SIGNAL_ID = "speed"
private const val DEMAND_OWNER = "gps-sim"
private const val SPEED_MAX_AGE_MS = 3_000L
private const val SIMULATED_ACCURACY_M = 3.0
private const val SIMULATED_SATELLITES = 12
private const val KMH_PER_MPS = 3.6

/** The car starts this far before the line, so the first lap begins moments after the start. */
private const val START_OFFSET_M = -40.0

/**
 * Drives a [SimulatedCircuit] at the vehicle speed of the OBD link (the simulator's speed signal),
 * delivering 5 fixes per second, so laps can be timed without a car: the lap time follows how the
 * simulator drives. Only wired in when the simulator transport is selected (see [LocationHub]). The
 * speed signal is demanded from the poller while running. A missing or stale speed stands still.
 *
 * [clock] is the wall clock for fix times and the integration step; both are injected for tests.
 */
class SimulatedLocationSource(
    private val poller: StateFlow<ObdPoller?>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val circuit: SimulatedCircuit = SimulatedCircuit(),
) : LocationSource {
    private val store = FixStore(clock)
    private val _status = MutableStateFlow<GpsStatus>(GpsStatus.Idle)
    private var job: Job? = null
    private var demandedOn: ObdPoller? = null

    @Volatile
    override var isRunning = false
        private set

    override val usesDeviceGps = false
    override val fix: StateFlow<GeoFix?> = store.fix
    override val status: StateFlow<GpsStatus> = _status.asStateFlow()

    override fun freshFix(maxAgeMs: Long): GeoFix? = store.fresh(maxAgeMs)

    override fun canStart() = true

    override fun start() {
        if (isRunning) return
        isRunning = true
        job = scope.launch { drive() }
    }

    override fun stop() {
        if (!isRunning) return
        isRunning = false
        job?.cancel()
        job = null
        demandedOn?.release(DEMAND_OWNER)
        demandedOn = null
        store.clear()
        _status.value = GpsStatus.Idle
    }

    override fun refresh() = Unit

    private suspend fun drive() {
        var distanceM = START_OFFSET_M
        var lastMs = clock()
        demandSpeed(poller.value)
        while (true) {
            delay(SIMULATED_FIX_INTERVAL_MS)
            val nowMs = clock()
            val speedMps = currentSpeedKmh(nowMs) / KMH_PER_MPS
            distanceM += speedMps * (nowMs - lastMs) / MS_PER_SECOND
            lastMs = nowMs
            publish(circuit.pointAt(distanceM), speedMps, nowMs)
        }
    }

    private fun publish(point: Pair<LatLon, Double>, speedMps: Double, nowMs: Long) {
        val fix = GeoFix(
            nowMs, point.first.lat, point.first.lon, speedMps, point.second, SIMULATED_ACCURACY_M, SIMULATED_SATELLITES,
        )
        store.onFix(fix, nowMs)
        _status.value = GpsStatus.Fix(SIMULATED_ACCURACY_M, SIMULATED_SATELLITES, store.rateHz)
    }

    /** The OBD vehicle speed of the current poller; 0 when it is not known or stale. */
    private fun currentSpeedKmh(nowMs: Long): Double {
        val current = poller.value
        demandSpeed(current)
        val reading = current?.readings?.value?.get(SPEED_SIGNAL_ID) ?: return 0.0
        return if (nowMs - reading.timestampMs <= SPEED_MAX_AGE_MS) reading.value else 0.0
    }

    /** A reconnect gives a new poller, which has to be asked for the speed again. */
    private fun demandSpeed(current: ObdPoller?) {
        if (current === demandedOn) return
        current?.demand(DEMAND_OWNER, setOf(SPEED_SIGNAL_ID))
        demandedOn = current
    }

    private companion object {
        const val MS_PER_SECOND = 1000.0
    }
}
