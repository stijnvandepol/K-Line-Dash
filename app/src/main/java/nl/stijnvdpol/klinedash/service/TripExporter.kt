package nl.stijnvdpol.klinedash.service

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import nl.stijnvdpol.klinedash.core.exportformat.GpxWriter
import nl.stijnvdpol.klinedash.core.exportformat.PcCsvWriter
import nl.stijnvdpol.klinedash.core.exportformat.TripExportInput
import nl.stijnvdpol.klinedash.core.exportformat.VboWriter
import nl.stijnvdpol.klinedash.core.trip.TripCsv
import nl.stijnvdpol.klinedash.data.TripDao

private const val TAG = "TripExporter"
private const val BYTE_ORDER_MARK = "\uFEFF"

/** A format that was not written, with a sentence for the user. */
data class SkippedExport(val format: ExportFormat, val reason: String)

/** Result of [TripExporter.exportTrip]. */
sealed interface ExportResult {
    /** [files] are the written files, in [ExportFormat] order; [skipped] the requested formats that make no sense for this trip. */
    data class Done(val files: List<File>, val skipped: List<SkippedExport>) : ExportResult
    data object TripNotFound : ExportResult

    /** The trip is being recorded right now; its file is not complete. */
    data object IsRecording : ExportResult
    data class Failed(val reason: String) : ExportResult
}

/**
 * Generates the PC files of a trip on demand ([exportTrip]) into the trips folder, next to the CSV,
 * from the CSV and the trip's current track. Where they go from there (Wi-Fi export, USB copy,
 * delete with the trip) is decided by [ExportFormat] and the file helpers, not here.
 *
 * Everything runs off the main thread. Files are written under a temporary name and moved into
 * place, so the export server never offers a half-written file. They are always rewritten: a
 * changed track gives different laps. All text is UTF-8; the two CSVs start with a byte order mark
 * (Excel on Windows needs it), the VBO and GPX do not (VBO readers expect plain text, XML does not
 * want one); the VBO has CR+LF line ends as the format requires.
 */
class TripExporter internal constructor(
    private val tripsDir: File,
    private val tripDao: TripDao,
    private val analysis: TripAnalysisRepository,
    private val recordingState: StateFlow<RecordingState>,
    private val clock: () -> Long,
    private val ioDispatcher: CoroutineDispatcher,
) {
    constructor(tripsDir: File, tripDao: TripDao, analysis: TripAnalysisRepository, recordingState: StateFlow<RecordingState>) :
        this(tripsDir, tripDao, analysis, recordingState, System::currentTimeMillis, Dispatchers.IO)

    /** Writes [formats] (default: all) for trip [tripId]; formats without data are reported in [ExportResult.Done.skipped]. */
    suspend fun exportTrip(tripId: Long, formats: Set<ExportFormat> = ExportFormat.entries.toSet()): ExportResult {
        val trip = tripDao.get(tripId) ?: return ExportResult.TripNotFound
        if ((recordingState.value as? RecordingState.Recording)?.fileName == trip.fileName) return ExportResult.IsRecording
        val source = resolveTripFile(tripsDir, trip.fileName)?.takeIf { it.isFile }
            ?: return ExportResult.Failed("The trip file is missing.")
        val detail = when (val result = analysis.analyze(tripId)) {
            is AnalysisResult.Done -> result.detail
            AnalysisResult.TripNotFound -> return ExportResult.TripNotFound
            is AnalysisResult.Failed -> return ExportResult.Failed(result.reason)
        }
        val plan = ExportFormat.entries.filter { it in formats }.associateWith { skipReason(it, detail) }
        return try {
            withContext(ioDispatcher) {
                val files = plan.filterValues { it == null }.keys.map { writeFormat(it, trip.fileName, source, detail) }
                ExportResult.Done(files, plan.mapNotNull { (format, reason) -> reason?.let { SkippedExport(format, it) } })
            }
        } catch (e: IOException) {
            Log.e(TAG, "Exporting ${trip.fileName} failed", e)
            ExportResult.Failed("Could not write the export files. Check the free storage of the head unit.")
        }
    }

    /** Removes the generated files of the trip file [tripFileName]; they are made again by the next export. */
    suspend fun deleteExports(tripFileName: String) = withContext(ioDispatcher) {
        derivedFiles(tripsDir, tripFileName).forEach { Files.deleteIfExists(it.toPath()) }
    }

    private fun skipReason(format: ExportFormat, detail: TripDetail): String? = when {
        format.needsGps && !detail.hasGps -> "This trip has no GPS data."
        format == ExportFormat.LAPS_CSV && detail.laps.isEmpty() ->
            if (detail.track == null) "No track was set for this trip, so there are no laps." else "No lap was completed on this track."
        else -> null
    }

    private fun writeFormat(format: ExportFormat, tripFileName: String, source: File, detail: TripDetail): File {
        val target = File(tripsDir, exportFileName(tripFileName, format))
        val partial = File(tripsDir, target.name + PARTIAL_FILE_SUFFIX)
        try {
            BufferedWriter(OutputStreamWriter(partial.outputStream(), Charsets.UTF_8)).use { out ->
                withTripCsv(source) { csv -> write(format, csv, tripFileName, detail, out) }
            }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(partial.toPath())
        }
        return target
    }

    private fun write(format: ExportFormat, csv: TripCsv, tripFileName: String, detail: TripDetail, out: Appendable) {
        val input = TripExportInput(
            title = tripFileName.removeSuffix(".csv"),
            createdMs = clock(),
            columns = csv.columns,
            samples = csv.samples,
            laps = detail.laps.map { it.lap },
            gates = detail.track?.lapGates(),
        )
        when (format) {
            ExportFormat.VBO -> VboWriter.write(input, out)
            ExportFormat.GPX -> GpxWriter.write(input, out)
            ExportFormat.PC_CSV -> PcCsvWriter.writeTrip(input, out.also { it.append(BYTE_ORDER_MARK) })
            ExportFormat.LAPS_CSV -> PcCsvWriter.writeLapSummary(detail.laps, out.also { it.append(BYTE_ORDER_MARK) })
        }
    }
}
