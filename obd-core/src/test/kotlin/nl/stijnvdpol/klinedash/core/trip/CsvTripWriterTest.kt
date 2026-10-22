package nl.stijnvdpol.klinedash.core.trip

import java.util.Locale
import nl.stijnvdpol.klinedash.core.assertFails
import nl.stijnvdpol.klinedash.core.obd.PollTier
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.obd.Signal
import nl.stijnvdpol.klinedash.core.obd.Signals
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTripWriterTest {

    private val coolant = Signals.byId("coolant")!!
    private val rpm = Signals.byId("rpm")!!
    private val timing = Signals.byId("timing")!!
    private val battery = Signals.byId("battery")!!
    private val mil = Signals.byId("mil_on")!!

    /** 2026-10-22T14:03:07.250Z */
    private val t0 = 1_792_677_787_250L

    private fun reading(signal: Signal, value: Double, at: Long) = signal.id to Reading(signal.id, value, at)

    private fun writer(out: StringBuilder, freshnessMs: Long = 30_000L) = CsvTripWriter(out, freshnessMs)

    @Test
    fun headerHasTimeElapsedAndOneNamedColumnPerSignal() {
        val out = StringBuilder()
        writer(out).writeHeader(listOf(rpm, coolant, battery, mil))
        assertEquals("time_iso,elapsed_s,rpm (rpm),coolant (°C),battery (V),mil_on\n", out.toString())
    }

    @Test
    fun headerFieldsWithCommasOrQuotesAreEscaped() {
        val odd = Signal("a,b", "01FF", 0xFF, "odd", "odd", "\"u\"", 0.0, 1.0, 0, PollTier.FAST) { null }
        val out = StringBuilder()
        writer(out).writeHeader(listOf(odd))
        assertEquals("time_iso,elapsed_s,\"a,b (\"\"u\"\")\"\n", out.toString())
    }

    @Test
    fun rowHasIsoTimeWithMillisecondsElapsedAndFormattedValues() {
        val out = StringBuilder()
        val csv = writer(out)
        csv.writeHeader(listOf(rpm, coolant, timing))
        out.setLength(0)

        csv.writeSample(t0, mapOf(reading(rpm, 2_345.4, t0), reading(coolant, 92.0, t0), reading(timing, 12.5, t0)))
        csv.writeSample(t0 + 1_250, mapOf(reading(rpm, 3_000.0, t0 + 1_250), reading(coolant, 92.0, t0), reading(timing, -3.0, t0)))

        assertEquals(
            "2026-10-22T14:03:07.250Z,0.0,2345,92,12.5\n" +
                "2026-10-22T14:03:08.500Z,1.3,3000,92,-3.0\n",
            out.toString(),
        )
    }

    @Test
    fun wholeSecondTimestampsStillShowMilliseconds() {
        val out = StringBuilder()
        val csv = writer(out)
        csv.writeHeader(listOf(rpm))
        out.setLength(0)
        csv.writeSample(1_700_000_000_000L, mapOf(reading(rpm, 800.0, 1_700_000_000_000L)))
        assertEquals("2023-11-14T22:13:20.000Z,0.0,800\n", out.toString())
    }

    @Test
    fun missingAndStaleValuesBecomeEmptyCells() {
        val out = StringBuilder()
        val csv = writer(out, freshnessMs = 10_000)
        csv.writeHeader(listOf(rpm, coolant, battery))
        out.setLength(0)

        val readings = mapOf(reading(rpm, 900.0, t0 - 10_000), reading(coolant, 90.0, t0 - 10_001))
        csv.writeSample(t0, readings)

        assertEquals("2026-10-22T14:03:07.250Z,0.0,900,,\n", out.toString())
    }

    @Test
    fun nonFiniteValuesBecomeEmptyCells() {
        val out = StringBuilder()
        val csv = writer(out)
        csv.writeHeader(listOf(rpm))
        out.setLength(0)
        csv.writeSample(t0, mapOf(reading(rpm, Double.NaN, t0)))
        assertEquals("2026-10-22T14:03:07.250Z,0.0,\n", out.toString())
    }

    @Test
    fun valuesUseTheSignalsDecimals() {
        val out = StringBuilder()
        val csv = writer(out)
        csv.writeHeader(listOf(battery, rpm))
        out.setLength(0)
        csv.writeSample(t0, mapOf(reading(battery, 13.84, t0), reading(rpm, 799.6, t0)))
        assertEquals("2026-10-22T14:03:07.250Z,0.0,13.8,800\n", out.toString())
    }

    @Test
    fun outputDoesNotDependOnTheDefaultLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val out = StringBuilder()
            val csv = writer(out)
            csv.writeHeader(listOf(battery, timing))
            out.setLength(0)
            csv.writeSample(t0, mapOf(reading(battery, 13.84, t0), reading(timing, 12.5, t0)))
            csv.writeSample(t0 + 1_250, mapOf(reading(battery, 13.84, t0), reading(timing, 12.5, t0)))
            assertEquals(
                "2026-10-22T14:03:07.250Z,0.0,13.8,12.5\n2026-10-22T14:03:08.500Z,1.3,13.8,12.5\n",
                out.toString(),
            )
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun writingASampleBeforeTheHeaderFails() {
        val failure = assertFails<IllegalStateException> { writer(StringBuilder()).writeSample(t0, emptyMap()) }
        assertEquals("writeHeader must be called before writeSample", failure.message)
    }

    @Test
    fun writingTheHeaderTwiceFails() {
        val csv = writer(StringBuilder())
        csv.writeHeader(listOf(rpm))
        assertFails<IllegalStateException> { csv.writeHeader(listOf(rpm)) }
    }
}
