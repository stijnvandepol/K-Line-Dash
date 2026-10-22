package nl.stijnvdpol.klinedash.core.trip

import nl.stijnvdpol.klinedash.core.obd.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripSummarizerTest {

    private val t0 = 5_000_000L

    private fun TripSummarizer.sample(
        nowMs: Long,
        speed: Double? = null,
        rpm: Double? = null,
        coolant: Double? = null,
        speedAgeMs: Long = 0,
    ) {
        val readings = buildMap {
            speed?.let { put("speed", Reading("speed", it, nowMs - speedAgeMs)) }
            rpm?.let { put("rpm", Reading("rpm", it, nowMs)) }
            coolant?.let { put("coolant", Reading("coolant", it, nowMs)) }
        }
        add(nowMs, readings)
    }

    @Test
    fun noSamplesGiveNoSummary() {
        assertNull(TripSummarizer().summary())
    }

    @Test
    fun singleSampleHasNoDistanceButKeepsTimesAndMaxima() {
        val summarizer = TripSummarizer()
        summarizer.sample(t0, speed = 50.0, rpm = 3000.0, coolant = 88.0)
        val summary = summarizer.summary()!!
        assertEquals(t0, summary.startMs)
        assertEquals(t0, summary.endMs)
        assertEquals(1, summary.sampleCount)
        assertEquals(0.0, summary.distanceKm, 0.0)
        assertEquals(50.0, summary.maxSpeedKmh!!, 0.0)
        assertEquals(3000.0, summary.maxRpm!!, 0.0)
        assertEquals(88.0, summary.maxCoolantC!!, 0.0)
    }

    @Test
    fun constantSpeedIntegratesToDistance() {
        val summarizer = TripSummarizer()
        for (second in 0..100 step 10) summarizer.sample(t0 + second * 1_000L, speed = 36.0)
        val summary = summarizer.summary()!!
        assertEquals(1.0, summary.distanceKm, 1e-9)
        assertEquals(11, summary.sampleCount)
        assertEquals(t0 + 100_000, summary.endMs)
    }

    @Test
    fun changingSpeedUsesTheAverageOfTheTwoSamples() {
        val summarizer = TripSummarizer()
        summarizer.sample(t0, speed = 0.0)
        summarizer.sample(t0 + 10_000, speed = 72.0)
        assertEquals(0.1, summarizer.summary()!!.distanceKm, 1e-9)
    }

    @Test
    fun gapLongerThanTheMaximumAddsNoDistance() {
        val summarizer = TripSummarizer()
        summarizer.sample(t0, speed = 100.0)
        summarizer.sample(t0 + 5_000, speed = 100.0)
        summarizer.sample(t0 + 5_000 + 10_001, speed = 100.0)
        summarizer.sample(t0 + 5_000 + 10_001 + 5_000, speed = 100.0)
        val expectedKm = 100.0 * 10.0 / 3600.0
        assertEquals(expectedKm, summarizer.summary()!!.distanceKm, 1e-9)
    }

    @Test
    fun gapExactlyAtTheMaximumStillCounts() {
        val summarizer = TripSummarizer(maxGapMs = 10_000)
        summarizer.sample(t0, speed = 36.0)
        summarizer.sample(t0 + 10_000, speed = 36.0)
        assertEquals(0.1, summarizer.summary()!!.distanceKm, 1e-9)
    }

    @Test
    fun missingOrStaleSpeedAddsNoDistanceButIsNotTreatedAsZero() {
        val summarizer = TripSummarizer(freshnessMs = 5_000)
        summarizer.sample(t0, speed = 36.0)
        summarizer.sample(t0 + 5_000)
        summarizer.sample(t0 + 10_000, speed = 36.0, speedAgeMs = 5_001)
        summarizer.sample(t0 + 15_000, speed = 36.0)
        summarizer.sample(t0 + 20_000, speed = 36.0)
        assertEquals(0.05, summarizer.summary()!!.distanceKm, 1e-9)
        assertEquals(36.0, summarizer.summary()!!.maxSpeedKmh!!, 0.0)
    }

    @Test
    fun outOfOrderSampleAddsNoDistance() {
        val summarizer = TripSummarizer()
        summarizer.sample(t0 + 5_000, speed = 36.0)
        summarizer.sample(t0, speed = 36.0)
        assertEquals(0.0, summarizer.summary()!!.distanceKm, 0.0)
    }

    @Test
    fun maximaIgnoreStaleAndMissingValues() {
        val summarizer = TripSummarizer(freshnessMs = 5_000)
        summarizer.sample(t0, speed = 80.0, rpm = 4_000.0, coolant = 90.0)
        summarizer.sample(t0 + 1_000, speed = 120.0, rpm = 6_500.0, coolant = 95.0)
        summarizer.sample(t0 + 2_000, speed = 60.0, rpm = 2_000.0)
        summarizer.sample(t0 + 3_000, speed = 200.0, speedAgeMs = 5_001)
        val summary = summarizer.summary()!!
        assertEquals(120.0, summary.maxSpeedKmh!!, 0.0)
        assertEquals(6_500.0, summary.maxRpm!!, 0.0)
        assertEquals(95.0, summary.maxCoolantC!!, 0.0)
        assertEquals(4, summary.sampleCount)
    }

    @Test
    fun maximaStayNullWhenNeverPolled() {
        val summarizer = TripSummarizer()
        summarizer.sample(t0)
        summarizer.sample(t0 + 1_000)
        val summary = summarizer.summary()!!
        assertNull(summary.maxRpm)
        assertNull(summary.maxSpeedKmh)
        assertNull(summary.maxCoolantC)
        assertEquals(0.0, summary.distanceKm, 0.0)
    }
}
