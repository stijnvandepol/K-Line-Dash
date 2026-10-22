package nl.stijnvdpol.klinedash.core.trip

/**
 * Statistics of a trip (or of one lap), from [TripStatsAccumulator].
 *
 * @property distanceKm GPS distance where fixes exist plus integrated vehicle speed where they do not.
 * @property gpsDistanceKm the part of [distanceKm] that comes from GPS.
 * @property movingTimeMs time at 2 km/h or more; [idleTimeMs] is time below that with the engine
 *   running (rpm above 300). Time with an unknown speed counts as neither.
 * @property maxSpeedKmh GPS (Doppler) speed when a fresh fix exists, vehicle speed otherwise;
 *   [minSpeedKmh] likewise.
 * @property fullThrottleTimeMs time with the throttle above 90 %. Its resolution is the sample rate.
 * @property estimatedFuelLiters ESTIMATE from the mass air flow for a petrol engine at a fixed
 *   air-fuel ratio, null when no air flow was recorded. Wrong while fuel is cut on deceleration or
 *   enriched at full throttle; use it to compare trips, not as a fuel gauge.
 */
data class TripStats(
    val startMs: Long,
    val endMs: Long,
    val sampleCount: Int,
    val distanceKm: Double,
    val gpsDistanceKm: Double,
    val movingTimeMs: Long,
    val idleTimeMs: Long,
    val maxSpeedKmh: Double?,
    val minSpeedKmh: Double?,
    val maxRpm: Double?,
    val maxCoolantC: Double?,
    val fullThrottleTimeMs: Long,
    val estimatedFuelLiters: Double?,
) {
    val durationMs: Long get() = endMs - startMs

    /** Distance over total duration; null for a zero-length trip. */
    val averageSpeedKmh: Double? get() = durationMs.takeIf { it > 0 }?.let { distanceKm / (it / MS_PER_HOUR) }

    /** Distance over moving time; null when the car never moved. */
    val averageMovingSpeedKmh: Double? get() = movingTimeMs.takeIf { it > 0 }?.let { distanceKm / (it / MS_PER_HOUR) }

    /** Estimated litres per 100 km; null without a fuel estimate or below [MIN_KM_FOR_CONSUMPTION]. */
    val estimatedFuelLitersPer100Km: Double?
        get() = estimatedFuelLiters?.takeIf { distanceKm >= MIN_KM_FOR_CONSUMPTION }?.let { it * KM_PER_100 / distanceKm }

    private companion object {
        const val MS_PER_HOUR = 3_600_000.0
        const val MIN_KM_FOR_CONSUMPTION = 0.5
        const val KM_PER_100 = 100.0
    }
}
