package nl.stijnvdpol.klinedash.core.exportformat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcCsvWriterTest {

    @Test
    fun tripCsvMatchesTheGoldenFile() {
        val text = written { PcCsvWriter.writeTrip(ExportFixture.input(), it) }
        assertEquals(ExportFixture.golden("trip.pc.csv"), text)
    }

    @Test
    fun lapSummaryMatchesTheGoldenFile() {
        val text = written { PcCsvWriter.writeLapSummary(ExportFixture.lapStats, it) }
        assertEquals(ExportFixture.golden("trip.laps.csv"), text)
    }

    @Test
    fun tripWithoutLapsHasAnEmptyLapColumn() {
        val text = written { PcCsvWriter.writeTrip(ExportFixture.input(withLaps = false), it) }
        val laps = text.lines().drop(1).filter { it.isNotEmpty() }.map { it.split(",")[2] }
        assertEquals(List(6) { "" }, laps)
    }

    @Test
    fun lapSummaryWithoutLapsIsJustTheHeader() {
        val text = written { PcCsvWriter.writeLapSummary(emptyList(), it) }
        assertEquals(1, text.lines().filter { it.isNotEmpty() }.size)
        assertTrue(text.startsWith("lap,valid,start_iso,lap_time (s)"))
    }

    @Test
    fun lapSummaryHasNoSectorColumnsWithoutSectors() {
        val text = written { PcCsvWriter.writeLapSummary(listOf(ExportFixture.lapStats[1]), it) }
        assertTrue(text.lines().first().endsWith("full_throttle (s)"))
        assertEquals("2,1,2026-10-22T14:03:10.500Z,3.250,0.000,0.100,110.8,70.0,40.0,3200,0.000", text.lines()[1])
    }
}
