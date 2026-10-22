package nl.stijnvdpol.klinedash.ui.trips

import java.io.File
import nl.stijnvdpol.klinedash.service.ExportFormat
import nl.stijnvdpol.klinedash.service.ExportResult
import nl.stijnvdpol.klinedash.service.SkippedExport
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportPresentationTest {
    @Test
    fun `the cells ask for VBO, GPX and the two CSV files`() {
        assertEquals(listOf("VBO", "GPX", "CSV"), ExportChoice.entries.map { it.label })
        assertEquals(setOf(ExportFormat.VBO), ExportChoice.VBO.formats)
        assertEquals(setOf(ExportFormat.GPX), ExportChoice.GPX.formats)
        assertEquals(setOf(ExportFormat.PC_CSV, ExportFormat.LAPS_CSV), ExportChoice.CSV.formats)
    }

    @Test
    fun `a finished export lists the file names and why a format was left out`() {
        val result = ExportResult.Done(
            files = listOf(File("/trips/trip-1.pc.csv")),
            skipped = listOf(SkippedExport(ExportFormat.LAPS_CSV, "No track was set for this trip, so there are no laps.")),
        )
        val outcome = exportOutcome(result) as ExportOutcome.Written
        assertEquals(listOf("trip-1.pc.csv"), outcome.fileNames)
        assertEquals(listOf("Lap CSV: No track was set for this trip, so there are no laps."), outcome.notes)
    }

    @Test
    fun `a refused export says what to do`() {
        assertEquals(true, (exportOutcome(ExportResult.IsRecording) as ExportOutcome.Failed).reason.contains("Stop it first"))
        assertEquals(TRIP_GONE, (exportOutcome(ExportResult.TripNotFound) as ExportOutcome.Failed).reason)
        assertEquals("Disk full.", (exportOutcome(ExportResult.Failed("Disk full.")) as ExportOutcome.Failed).reason)
    }
}
