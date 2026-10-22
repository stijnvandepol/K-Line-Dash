package nl.stijnvdpol.klinedash.ui.trips

import nl.stijnvdpol.klinedash.core.trip.TripStats
import nl.stijnvdpol.klinedash.ui.MISSING_VALUE
import org.junit.Assert.assertEquals
import org.junit.Test

class TripStatCellsTest {
    private fun stats(
        durationMs: Long = 1_500_000,
        distanceKm: Double = 31.26,
        movingTimeMs: Long = 1_200_000,
        maxSpeedKmh: Double? = 142.4,
        maxRpm: Double? = 7_150.0,
        maxCoolantC: Double? = 93.0,
        fullThrottleTimeMs: Long = 150_000,
        fuel: Double? = 2.46,
    ) = TripStats(
        startMs = 0, endMs = durationMs, sampleCount = 1_500, distanceKm = distanceKm, gpsDistanceKm = distanceKm,
        movingTimeMs = movingTimeMs, idleTimeMs = 0, maxSpeedKmh = maxSpeedKmh, minSpeedKmh = 0.0, maxRpm = maxRpm,
        maxCoolantC = maxCoolantC, fullThrottleTimeMs = fullThrottleTimeMs, estimatedFuelLiters = fuel,
    )

    private fun valueOf(cells: List<StatCell>, label: String) = cells.first { it.label == label }.value

    @Test
    fun `the table reads a trip in fixed formats`() {
        val cells = statCells(stats())
        assertEquals("25:00", valueOf(cells, "Duration"))
        assertEquals("31.3", valueOf(cells, "Distance"))
        assertEquals("20:00", valueOf(cells, "Moving time"))
        assertEquals("75", valueOf(cells, "Avg speed"))
        assertEquals("94", valueOf(cells, "Avg moving"))
        assertEquals("142", valueOf(cells, "Max speed"))
        assertEquals("7150", valueOf(cells, "Max RPM"))
        assertEquals("93", valueOf(cells, "Max coolant"))
        assertEquals("10", valueOf(cells, "Full throttle"))
        assertEquals("2.5", valueOf(cells, "Fuel (est.)"))
        assertEquals("7.9", valueOf(cells, "Fuel/100 km (est.)"))
    }

    @Test
    fun `a trip that was never measured shows dashes, never zeros`() {
        val cells = statCells(stats(maxSpeedKmh = null, maxRpm = null, maxCoolantC = null, fuel = null))
        listOf("Max speed", "Max RPM", "Max coolant", "Full throttle", "Fuel (est.)", "Fuel/100 km (est.)").forEach {
            assertEquals(it, MISSING_VALUE, valueOf(cells, it))
        }
    }

    @Test
    fun `a file without a readable row shows dashes everywhere`() {
        assertEquals(setOf(MISSING_VALUE), statCells(null).map { it.value }.toSet())
    }

    @Test
    fun `a short trip has no consumption per 100 km`() {
        assertEquals(MISSING_VALUE, valueOf(statCells(stats(distanceKm = 0.2)), "Fuel/100 km (est.)"))
    }

    @Test
    fun `the live fields are six in reading order`() {
        val cells = liveStatCells(stats())
        assertEquals(listOf("Time", "Distance", "Avg km/h", "Max km/h", "Max RPM", "Fuel"), cells.map { it.label })
        assertEquals(listOf("25:00", "31.3", "75", "142", "7150", "2.5"), cells.map { it.value })
        assertEquals("L est.", cells.last().unit)
    }

    @Test
    fun `the live fields are dashes until the first sample`() {
        assertEquals(setOf(MISSING_VALUE), liveStatCells(null).map { it.value }.toSet())
    }
}
