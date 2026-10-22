package nl.stijnvdpol.klinedash.ui.trips

import nl.stijnvdpol.klinedash.service.ExportFormat
import nl.stijnvdpol.klinedash.service.ExportResult
import nl.stijnvdpol.klinedash.ui.STILL_RECORDING_NOTICE

/*
 * What "Export for PC" says, as plain functions: the choices, the files written and why a format was
 * left out. The composables only draw the result.
 */

internal const val TRIP_GONE = "This trip no longer exists."

/** The cells of "Export for PC": what the user asks for, and the formats that make it. */
internal enum class ExportChoice(val label: String, val formats: Set<ExportFormat>) {
    VBO("VBO", setOf(ExportFormat.VBO)),
    GPX("GPX", setOf(ExportFormat.GPX)),

    /** The whole trip with a lap column, and the lap table when the trip has laps. */
    CSV("CSV", setOf(ExportFormat.PC_CSV, ExportFormat.LAPS_CSV)),
}

/** What an export did, in words for the strip under the cells. */
internal sealed interface ExportOutcome {
    /** [fileNames] were written; [notes] say why a requested format was left out. */
    data class Written(val fileNames: List<String>, val notes: List<String>) : ExportOutcome
    data class Failed(val reason: String) : ExportOutcome
}

private fun formatName(format: ExportFormat): String = when (format) {
    ExportFormat.VBO -> "VBO"
    ExportFormat.GPX -> "GPX"
    ExportFormat.PC_CSV -> "CSV"
    ExportFormat.LAPS_CSV -> "Lap CSV"
}

internal fun exportOutcome(result: ExportResult): ExportOutcome = when (result) {
    is ExportResult.Done -> ExportOutcome.Written(
        fileNames = result.files.map { it.name },
        notes = result.skipped.map { "${formatName(it.format)}: ${it.reason}" },
    )
    ExportResult.IsRecording -> ExportOutcome.Failed(STILL_RECORDING_NOTICE)
    ExportResult.TripNotFound -> ExportOutcome.Failed(TRIP_GONE)
    is ExportResult.Failed -> ExportOutcome.Failed(result.reason)
}
