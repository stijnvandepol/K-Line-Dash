package nl.stijnvdpol.klinedash.core.trip

import java.io.StringReader
import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.obd.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The GPS columns of [CsvTripWriter] and the tolerant reader [readTripCsv]. */
class TripCsvGpsTest {

    private val rpm = Signals.byId("rpm")!!
    private val coolant = Signals.byId("coolant")!!

    /** 2026-10-22T14:03:07.250Z */
    private val t0 = 1_792_677_787_250L

    private val fix = GeoFix(t0 - 400, 52.0123456, 4.9876543, speedMps = 12.5, bearingDeg = 271.34, accuracyM = 3.26, satellites = 9)

    private fun readings(at: Long) = mapOf(
        "rpm" to Reading("rpm", 2345.0, at),
        "coolant" to Reading("coolant", 92.0, at),
    )

    private fun written(includeGps: Boolean, vararg rows: Pair<Long, GeoFix?>): String {
        val out = StringBuilder()
        val csv = CsvTripWriter(out, includeGps = includeGps)
        csv.writeHeader(listOf(rpm, coolant))
        for ((time, gps) in rows) csv.writeSample(time, readings(time), gps)
        return out.toString()
    }

    private fun read(text: String): TripCsv = readTripCsv(StringReader(text))

    @Test
    fun headerGetsTheGpsColumnsAfterTheSignalsWhenEnabled() {
        val header = written(true).lines().first()
        assertEquals(
            "time_iso,elapsed_s,rpm (rpm),coolant (°C),lat (deg),lon (deg),gps_speed (km/h),heading (deg),gps_acc (m),sats,gps_age (ms)",
            header,
        )
    }

    @Test
    fun headerIsUnchangedWithoutGps() {
        assertEquals("time_iso,elapsed_s,rpm (rpm),coolant (°C)", written(false).lines().first())
    }

    @Test
    fun rowCarriesTheFixWithItsAge() {
        val row = written(true, t0 to fix).lines()[1]
        assertEquals("2026-10-22T14:03:07.250Z,0.0,2345,92,52.0123456,4.9876543,45.0,271.3,3.3,9,400", row)
    }

    @Test
    fun rowWithoutAFixOrWithAPartialFixHasEmptyGpsCells() {
        val partial = GeoFix(t0, 52.0, 5.0)
        val rows = written(true, t0 to null, t0 + 1_000 to partial).lines()
        assertEquals("2026-10-22T14:03:07.250Z,0.0,2345,92,,,,,,,", rows[1])
        assertEquals("2026-10-22T14:03:08.250Z,1.0,2345,92,52.0000000,5.0000000,,,,,1000", rows[2])
    }

    @Test
    fun gpsIsIgnoredWhenTheWriterDoesNotIncludeIt() {
        val row = written(false, t0 to fix).lines()[1]
        assertEquals("2026-10-22T14:03:07.250Z,0.0,2345,92", row)
    }

    @Test
    fun readerReturnsTheSamplesAndTheFixOfAGpsFile() {
        val csv = read(written(true, t0 to fix, t0 + 1_000 to null))

        assertEquals(listOf(TripColumn("rpm", "rpm"), TripColumn("coolant", "°C")), csv.columns)
        val samples = csv.samples.toList()
        assertEquals(2, samples.size)
        assertEquals(t0, samples[0].timeMs)
        assertEquals(mapOf("rpm" to 2345.0, "coolant" to 92.0), samples[0].values)
        val read = samples[0].gps!!
        assertEquals(t0 - 400, read.timeMs)
        assertEquals(52.0123456, read.lat, 1e-9)
        assertEquals(4.9876543, read.lon, 1e-9)
        assertEquals(12.5, read.speedMps!!, 0.02)
        assertEquals(271.3, read.bearingDeg!!, 1e-9)
        assertEquals(3.3, read.accuracyM!!, 1e-9)
        assertEquals(9, read.satellites)
        assertNull(samples[1].gps)
    }

    @Test
    fun oldFileWithoutGpsColumnsStillReads() {
        val old = "time_iso,elapsed_s,rpm (rpm),coolant (°C)\n2026-10-22T14:03:07.250Z,0.0,2345,92\n"
        val csv = read(old)
        assertEquals(listOf("rpm", "coolant"), csv.columns.map { it.id })
        val sample = csv.samples.single()
        assertEquals(2345.0, sample.values["rpm"]!!, 0.0)
        assertNull(sample.gps)
    }

    @Test
    fun byteOrderMarkAndWindowsLineEndsAreTolerated() {
        val text = "﻿time_iso,elapsed_s,rpm (rpm)\r\n2026-10-22T14:03:07.250Z,0.0,2345\r\n"
        val csv = read(text)
        assertEquals(listOf(TripColumn("rpm", "rpm")), csv.columns)
        assertEquals(2345.0, csv.samples.single().values["rpm"]!!, 0.0)
    }

    @Test
    fun cutOffLastLineIsDroppedEvenWhenItLooksComplete() {
        val text = "time_iso,elapsed_s,rpm (rpm)\n2026-10-22T14:03:07.250Z,0.0,2345\n2026-10-22T14:03:08.250Z,1.0,23"
        assertEquals(1, read(text).samples.count())
    }

    @Test
    fun emptyCellsMeanUnknownAndShortRowsAreFilledWithEmptyCells() {
        val text = "time_iso,elapsed_s,rpm (rpm),coolant (°C)\n" +
            "2026-10-22T14:03:07.250Z,0.0,,92\n" +
            "2026-10-22T14:03:08.250Z,1.0,2400\n" +
            "2026-10-22T14:03:09.250Z,2.0,abc,NaN\n"
        val samples = read(text).samples.toList()
        assertEquals(mapOf("coolant" to 92.0), samples[0].values)
        assertEquals(mapOf("rpm" to 2400.0), samples[1].values)
        assertTrue(samples[2].values.isEmpty())
    }

    @Test
    fun rowsWithAnUnreadableTimeAreSkipped() {
        val text = "time_iso,elapsed_s,rpm (rpm)\nnot a time,0.0,1\n2026-10-22T14:03:07.250Z,0.0,2\n\n"
        val samples = read(text).samples.toList()
        assertEquals(listOf(2.0), samples.map { it.values["rpm"] })
    }

    @Test
    fun fileWithoutATimeColumnHasNoSamples() {
        val csv = read("elapsed_s,rpm (rpm)\n0.0,2345\n")
        assertEquals(listOf("rpm"), csv.columns.map { it.id })
        assertEquals(0, csv.samples.count())
    }

    @Test
    fun emptyAndHeaderOnlyFilesGiveNoSamples() {
        assertEquals(0, read("").samples.count())
        assertTrue(read("").columns.isEmpty())
        assertEquals(0, read("time_iso,elapsed_s,rpm (rpm)\n").samples.count())
        assertEquals(0, read("time_iso,elapsed_s,rpm (rpm)").samples.count())
    }

    @Test
    fun invalidCoordinatesGiveNoFixInsteadOfAnError() {
        val text = "time_iso,elapsed_s,lat (deg),lon (deg)\n" +
            "2026-10-22T14:03:07.250Z,0.0,95.0,5.0\n" +
            "2026-10-22T14:03:08.250Z,1.0,abc,5.0\n"
        val samples = read(text).samples.toList()
        assertEquals(2, samples.size)
        assertTrue(samples.all { it.gps == null })
    }

    @Test
    fun quotedHeaderFieldsKeepTheirCommas() {
        val text = "time_iso,elapsed_s,\"a,b (\"\"u\"\")\"\n2026-10-22T14:03:07.250Z,0.0,1.5\n"
        val csv = read(text)
        assertEquals(listOf(TripColumn("a,b", "\"u\"")), csv.columns)
        assertEquals(1.5, csv.samples.single().values["a,b"]!!, 0.0)
    }

    @Test
    fun signalWithoutAUnitHasAnEmptyUnit() {
        val csv = read("time_iso,elapsed_s,mil_on\n2026-10-22T14:03:07.250Z,0.0,1\n")
        assertEquals(listOf(TripColumn("mil_on", "")), csv.columns)
        assertNotNull(csv.samples.single().values["mil_on"])
    }
}
