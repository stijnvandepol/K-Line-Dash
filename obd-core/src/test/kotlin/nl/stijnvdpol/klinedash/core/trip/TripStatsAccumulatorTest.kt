package nl.stijnvdpol.klinedash.core.trip

import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.geo.TestWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripStatsAccumulatorTest {

    private val t0 = TestWorld.T0

    private fun sample(
        second: Int,
        speed: Double? = null,
        rpm: Double? = null,
        throttle: Double? = null,
        coolant: Double? = null,
        maf: Double? = null,
        gps: GeoFix? = null,
    ) = TripSample(
        t0 + second * 1_000L,
        buildMap {
            speed?.let { put("speed", it) }
            rpm?.let { put("rpm", it) }
            throttle?.let { put("throttle", it) }
            coolant?.let { put("coolant", it) }
            maf?.let { put("maf", it) }
        },
        gps,
    )

    private fun statsOf(vararg samples: TripSample): TripStats {
        val accumulator = TripStatsAccumulator()
        samples.forEach(accumulator::add)
        return accumulator.stats()!!
    }

    /** A fix at [second] on the line driving north at [speedMps]. */
    private fun fixAt(second: Int, speedMps: Double? = null, northM: Double = second * 20.0) =
        TestWorld.fix(t0 + second * 1_000L, 0.0, northM, speedMps = speedMps)

    @Test
    fun noSamplesGiveNoStats() {
        assertNull(TripStatsAccumulator().stats())
    }

    @Test
    fun singleSampleHasDurationZeroAndNoAverages() {
        val stats = statsOf(sample(0, speed = 50.0, rpm = 3000.0, coolant = 88.0))
        assertEquals(0L, stats.durationMs)
        assertEquals(1, stats.sampleCount)
        assertEquals(0.0, stats.distanceKm, 0.0)
        assertNull(stats.averageSpeedKmh)
        assertNull(stats.averageMovingSpeedKmh)
        assertEquals(50.0, stats.maxSpeedKmh!!, 0.0)
        assertEquals(50.0, stats.minSpeedKmh!!, 0.0)
        assertEquals(3000.0, stats.maxRpm!!, 0.0)
        assertEquals(88.0, stats.maxCoolantC!!, 0.0)
    }

    @Test
    fun vehicleSpeedIsIntegratedWhenThereIsNoGps() {
        val stats = statsOf(*(0..9).map { sample(it, speed = 36.0, rpm = 2000.0) }.toTypedArray())
        assertEquals(0.09, stats.distanceKm, 1e-9)
        assertEquals(0.0, stats.gpsDistanceKm, 0.0)
        assertEquals(9_000L, stats.durationMs)
        assertEquals(9_000L, stats.movingTimeMs)
        assertEquals(0L, stats.idleTimeMs)
        assertEquals(36.0, stats.averageSpeedKmh!!, 1e-9)
        assertEquals(36.0, stats.averageMovingSpeedKmh!!, 1e-9)
    }

    @Test
    fun idleIsStandstillWithTheEngineRunningAndMovingStartsAtTwoKmh() {
        val stats = statsOf(
            sample(0, speed = 0.0, rpm = 800.0),
            sample(1, speed = 1.9, rpm = 800.0),
            sample(2, speed = 2.0, rpm = 900.0),
            sample(3, speed = 0.0, rpm = 0.0),
            sample(4, speed = 0.0, rpm = 0.0),
        )
        assertEquals(2_000L, stats.idleTimeMs)
        assertEquals(1_000L, stats.movingTimeMs)
    }

    @Test
    fun timeWithUnknownSpeedIsNeitherMovingNorIdle() {
        val stats = statsOf(sample(0, rpm = 800.0), sample(1, rpm = 800.0), sample(2, rpm = 800.0))
        assertEquals(0L, stats.idleTimeMs)
        assertEquals(0L, stats.movingTimeMs)
    }

    @Test
    fun intervalLongerThanTheMaximumGapCountsNothing() {
        val stats = statsOf(
            sample(0, speed = 100.0, throttle = 100.0, maf = 20.0),
            sample(30, speed = 100.0, throttle = 100.0, maf = 20.0),
        )
        assertEquals(0.0, stats.distanceKm, 0.0)
        assertEquals(0L, stats.movingTimeMs)
        assertEquals(0L, stats.fullThrottleTimeMs)
        assertNull(stats.estimatedFuelLiters)
        assertEquals(30_000L, stats.durationMs)
    }

    @Test
    fun fullThrottleIsTimeAboveNinetyPercent() {
        val stats = statsOf(
            sample(0, throttle = 20.0),
            sample(1, throttle = 95.0),
            sample(2, throttle = 100.0),
            sample(3, throttle = 90.0),
            sample(4, throttle = 10.0),
        )
        assertEquals(2_000L, stats.fullThrottleTimeMs)
    }

    @Test
    fun fuelEstimateIntegratesTheAirFlowForPetrol() {
        val samples = (0..360).map { sample(it * 10, speed = 100.0, maf = 10.0) }
        val stats = statsOf(*samples.toTypedArray())

        val expectedLiters = 10.0 * 3_600.0 / (14.7 * 745.0)
        assertEquals(0.3287, 3_600.0 / (14.7 * 745.0), 1e-4)
        assertEquals(expectedLiters, stats.estimatedFuelLiters!!, 1e-9)
        assertEquals(100.0, stats.distanceKm, 1e-9)
        assertEquals(expectedLiters, stats.estimatedFuelLitersPer100Km!!, 1e-9)
    }

    @Test
    fun noAirFlowMeansNoFuelEstimate() {
        val stats = statsOf(sample(0, speed = 100.0), sample(1, speed = 100.0))
        assertNull(stats.estimatedFuelLiters)
        assertNull(stats.estimatedFuelLitersPer100Km)
    }

    @Test
    fun consumptionPerHundredKilometresNeedsAMinimumDistance() {
        val stats = statsOf(sample(0, speed = 10.0, maf = 5.0), sample(10, speed = 10.0, maf = 5.0))
        assertNull(stats.estimatedFuelLitersPer100Km)
    }

    @Test
    fun gpsDistanceIsPreferredOverVehicleSpeedAndNotAddedTwice() {
        val samples = (0..10).map { sample(it, speed = 100.0, gps = fixAt(it, speedMps = 20.0)) }
        val stats = statsOf(*samples.toTypedArray())

        assertEquals(0.2, stats.distanceKm, 0.001)
        assertEquals(stats.distanceKm, stats.gpsDistanceKm, 0.0)
        assertEquals(72.0, stats.maxSpeedKmh!!, 1e-9)
        assertEquals(72.0, stats.minSpeedKmh!!, 1e-9)
    }

    @Test
    fun repeatedFixesAreCountedOnce() {
        val samples = (0..10).flatMap { listOf(sample(it, gps = fixAt(it, 20.0)), sample(it, gps = fixAt(it, 20.0))) }
        assertEquals(0.2, statsOf(*samples.toTypedArray()).gpsDistanceKm, 0.001)
    }

    @Test
    fun gpsJitterWhileStandingStillAddsNoDistance() {
        val samples = (0..10).map { second ->
            val wobble = if (second % 2 == 0) 0.0 else 4.0
            sample(second, gps = TestWorld.fix(t0 + second * 1_000L, 0.0, wobble, speedMps = 0.0))
        }
        assertEquals(0.0, statsOf(*samples.toTypedArray()).distanceKm, 0.0)
    }

    @Test
    fun fixPairWithAGpsDropoutBetweenThemAddsNoDistance() {
        val stats = statsOf(
            sample(0, gps = fixAt(0, 20.0)),
            sample(1, gps = fixAt(1, 20.0)),
            sample(8, gps = fixAt(8, 20.0, northM = 160.0)),
            sample(9, gps = fixAt(9, 20.0, northM = 180.0)),
        )
        assertEquals(0.04, stats.gpsDistanceKm, 0.001)
    }

    @Test
    fun vehicleSpeedTakesOverWhenGpsDropsOut() {
        val samples = (0..10).map { second ->
            sample(second, speed = 72.0, gps = fixAt(minOf(second, 5), 20.0))
        }
        val stats = statsOf(*samples.toTypedArray())

        assertEquals(0.1, stats.gpsDistanceKm, 0.001)
        // The repeated last fix still counts as fresh for 2 s (intervals 5-6 and 6-7), and no new fix
        // arrives to add distance, so those 2 s are lost; 7-8 to 9-10 come from the OBD speed.
        assertEquals(0.16, stats.distanceKm, 0.001)
    }

    @Test
    fun gpsSpeedWinsOverVehicleSpeedOnlyWhileTheFixIsFresh() {
        val stats = statsOf(
            sample(0, speed = 80.0, gps = fixAt(0, 20.0)),
            sample(10, speed = 80.0, gps = fixAt(0, 20.0)),
        )
        assertEquals(80.0, stats.maxSpeedKmh!!, 1e-9)
        assertEquals(72.0, statsOf(sample(0, speed = 80.0, gps = fixAt(0, 20.0))).maxSpeedKmh!!, 1e-9)
    }

    @Test
    fun tripSampleOfDropsStaleAndNonFiniteReadings() {
        val readings = mapOf(
            "rpm" to nl.stijnvdpol.klinedash.core.obd.Reading("rpm", 2000.0, t0 - 1_000),
            "coolant" to nl.stijnvdpol.klinedash.core.obd.Reading("coolant", 90.0, t0 - 31_000),
            "speed" to nl.stijnvdpol.klinedash.core.obd.Reading("speed", Double.NaN, t0),
        )
        assertEquals(mapOf("rpm" to 2000.0), tripSampleOf(t0, readings).values)
    }
}
