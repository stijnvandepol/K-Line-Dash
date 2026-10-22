package nl.stijnvdpol.klinedash.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.geo.isValidPosition

private const val TAG = "LocationTracker"
private const val STATUS_REFRESH_MS = 1_000L
private const val SATELLITES_EXTRA = "satellites"

/**
 * The device's GPS through the plain [LocationManager] (no Google Play Services), asking for the
 * fastest rate the chip offers: minimum interval 0 ms and distance 0 m. What the chip really
 * delivers is measured ([GpsStatus.Fix.rateHz]) and logged once, because phones and head units
 * differ (1 Hz is common, some reach 5-10 Hz).
 *
 * Satellite count: fixes carry the number of satellites used in the solution when the system gives
 * it, else the count from [GnssStatus]. Needs ACCESS_FINE_LOCATION; approximate-only is useless for
 * lap timing and reported as [GpsStatus.NoPermission]. It does not request permissions: the UI does
 * and then calls [refresh].
 *
 * Calls and callbacks run on the main thread; [fix] and [status] may be read from anywhere. While
 * running, [status] is re-evaluated every second so a fix that stops arriving turns into
 * [GpsStatus.Searching] by itself.
 *
 * Testing without a car: in the emulator send positions with `adb emu geo fix <lon> <lat>`
 * (repeat it to move), or use the simulator transport, which drives a simulated circuit
 * ([SimulatedLocationSource]).
 */
class LocationTracker(
    context: Context,
    private val scope: CoroutineScope,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
) : LocationSource {
    private val appContext = context.applicationContext
    private val manager: LocationManager? = appContext.getSystemService(LocationManager::class.java)
    private val store = FixStore(elapsedMs)
    private val handler = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow<GpsStatus>(GpsStatus.Idle)
    private var refreshJob: Job? = null
    private var isRateLogged = false

    @Volatile
    private var satellitesInFix: Int? = null

    @Volatile
    override var isRunning = false
        private set

    override val usesDeviceGps = true
    override val fix: StateFlow<GeoFix?> = store.fix
    override val status: StateFlow<GpsStatus> = _status.asStateFlow()

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = handleLocation(location)

        override fun onProviderEnabled(provider: String) = refresh()

        override fun onProviderDisabled(provider: String) = refresh()

        @Deprecated("Never called on API 29+, required before")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satellitesInFix = (0 until status.satelliteCount).count { status.usedInFix(it) }
        }
    }

    override fun freshFix(maxAgeMs: Long): GeoFix? = store.fresh(maxAgeMs)

    override fun canStart(): Boolean = hasFinePermission() && manager != null

    override fun start() {
        if (isRunning || !canStart()) return refresh()
        val locationManager = checkNotNull(manager)
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission was withdrawn while starting", e)
            return refresh()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "This device has no GPS provider", e)
            return refresh()
        }
        registerSatelliteCounter(locationManager)
        isRunning = true
        refreshJob = scope.launch {
            while (isActive) {
                delay(STATUS_REFRESH_MS)
                refresh()
            }
        }
        refresh()
    }

    /** Only for the satellite count in the status chip, so a failure here is logged and not fatal. */
    private fun registerSatelliteCounter(locationManager: LocationManager) {
        try {
            locationManager.registerGnssStatusCallback(gnssCallback, handler)
        } catch (e: SecurityException) {
            Log.w(TAG, "No satellite count: GNSS status needs the location permission", e)
        }
    }

    override fun stop() {
        if (!isRunning) return
        isRunning = false
        refreshJob?.cancel()
        refreshJob = null
        manager?.removeUpdates(listener)
        manager?.unregisterGnssStatusCallback(gnssCallback)
        store.clear()
        satellitesInFix = null
        refresh()
    }

    override fun refresh() {
        val latest = store.fresh(FIX_MAX_AGE_MS)
        _status.value = gpsStatusOf(hasFinePermission(), isGpsProviderEnabled(), isRunning, latest, store.rateHz)
    }

    private fun handleLocation(location: Location) {
        val fix = location.toGeoFix(satellitesInFix) ?: return
        store.onFix(fix, TimeUnit.NANOSECONDS.toMillis(location.elapsedRealtimeNanos))
        logRateOnce()
        refresh()
    }

    private fun logRateOnce() {
        if (isRateLogged || store.measuredIntervals < FixRateMeter.WINDOW) return
        isRateLogged = true
        val rate = store.rateHz ?: return
        Log.i(TAG, String.format(Locale.US, "Measured GPS fix rate: %.1f Hz (median interval %.0f ms)", rate, 1000.0 / rate))
    }

    private fun hasFinePermission() =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isGpsProviderEnabled(): Boolean = manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
}

/**
 * A fix from the system's [Location], or null when its position is not a valid position (some
 * chips report 0/NaN before they have one). Speed, bearing and accuracy are only taken when the
 * system says it has them; a zero time (never seen from real GPS) falls back to the wall clock.
 */
private fun Location.toGeoFix(satellites: Int?): GeoFix? {
    if (!isValidPosition(latitude, longitude)) return null
    return GeoFix(
        timeMs = time.takeIf { it > 0L } ?: System.currentTimeMillis(),
        lat = latitude,
        lon = longitude,
        speedMps = if (hasSpeed()) speed.toDouble() else null,
        bearingDeg = if (hasBearing()) bearing.toDouble() else null,
        accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
        satellites = extras?.getInt(SATELLITES_EXTRA, -1)?.takeIf { it >= 0 } ?: satellites?.takeIf { it > 0 },
    )
}
