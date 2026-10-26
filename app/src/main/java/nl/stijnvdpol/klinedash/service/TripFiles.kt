package nl.stijnvdpol.klinedash.service

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val TRIP_FILE_PREFIX = "trip-"
private const val TRIP_FILE_EXTENSION = "csv"
private const val FIRST_FILE_INDEX = 1
private val FILE_NAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

/**
 * "trip-20261022-140307.csv" for a trip started at [startMs] in [zone] (the user's wall clock, as
 * they will look for it on a USB stick). [index] above 1 is appended for a second trip started in
 * the same second: "trip-20261022-140307-2.csv".
 */
fun tripFileName(startMs: Long, zone: ZoneId, index: Int = FIRST_FILE_INDEX): String {
    require(index >= FIRST_FILE_INDEX) { "index must be at least $FIRST_FILE_INDEX" }
    val time = FILE_NAME_TIME.format(Instant.ofEpochMilli(startMs).atZone(zone))
    val suffix = if (index == FIRST_FILE_INDEX) "" else "-$index"
    return "$TRIP_FILE_PREFIX$time$suffix.$TRIP_FILE_EXTENSION"
}

/** "." and ".." pass the plain-name check but name the trips folder itself and its parent. */
private val DIRECTORY_ALIASES = setOf(".", "..")

/**
 * The file [fileName] inside [tripsDir], or null when the name is not a plain file name. Names come
 * from the database; a corrupted row must never reach outside the trips folder.
 */
fun resolveTripFile(tripsDir: File, fileName: String): File? {
    val isPlainName = fileName.isNotEmpty() && fileName !in DIRECTORY_ALIASES && File(fileName).name == fileName
    return if (isPlainName) File(tripsDir, fileName) else null
}

/**
 * [resolveTripFile] when that file exists, else null (bad name or file gone). It reads the file
 * system: call it off the main thread.
 */
fun resolveExistingTripFile(tripsDir: File, fileName: String): File? =
    resolveTripFile(tripsDir, fileName)?.takeIf { it.isFile }

/**
 * The files generated from a trip CSV for analysis on a PC, named after the trip: the trip
 * `trip-20261022-140307.csv` gives `trip-20261022-140307.vbo`, `.gpx`, `.pc.csv` and `.laps.csv`.
 * They live next to the trip, so the Wi-Fi export, the USB copy and the delete of the trip handle
 * them with it. They are a cache: gates may change, and the next export rewrites them.
 *
 * @property needsGps the file is empty without positions, so it is not written for a trip without GPS.
 */
enum class ExportFormat(val suffix: String, val needsGps: Boolean) {
    /** Racelogic VBO: Circuit Tools, RaceChrono, RaceRender. */
    VBO(".vbo", needsGps = true),

    /** GPX 1.1 with speed and heading: maps, GPS Visualizer, Google Earth. */
    GPX(".gpx", needsGps = true),

    /** The trip with a `lap` column, for Excel, LibreOffice, pandas. */
    PC_CSV(".pc.csv", needsGps = false),

    /** One row per lap with times, sectors and statistics. */
    LAPS_CSV(".laps.csv", needsGps = false),
}

/** The trip file name without its extension: "trip-20261022-140307" for "trip-20261022-140307.csv". */
internal fun tripBaseName(tripFileName: String): String = tripFileName.removeSuffix(".$TRIP_FILE_EXTENSION")

/** File name of [format] for the trip file [tripFileName] ("trip-20261022-140307.csv"). */
fun exportFileName(tripFileName: String, format: ExportFormat): String = tripBaseName(tripFileName) + format.suffix

/** The generated files of [tripFileName] that exist now. */
fun derivedFiles(tripsDir: File, tripFileName: String): List<File> =
    ExportFormat.entries.mapNotNull { resolveExistingTripFile(tripsDir, exportFileName(tripFileName, it)) }

/**
 * Half-written generated files of [tripFileName] that an export killed with the process left behind
 * (see [PARTIAL_FILE_SUFFIX]); they go away with the trip, they are never offered or copied.
 */
fun leftoverPartialFiles(tripsDir: File, tripFileName: String): List<File> =
    ExportFormat.entries.mapNotNull {
        resolveExistingTripFile(tripsDir, exportFileName(tripFileName, it) + PARTIAL_FILE_SUFFIX)
    }

/** Starts the CSV files: Excel on Windows misreads UTF-8 without it (the headers hold a degree sign). */
internal const val BYTE_ORDER_MARK = "\uFEFF"

/** Suffix of a file being written; it is renamed when complete, so nobody downloads half a file. */
const val PARTIAL_FILE_SUFFIX = ".part"

/**
 * Everything in [tripsDir] a user may take away, newest first: the trip CSVs and the files generated
 * from them (see [ExportFormat]). This is what the Wi-Fi export offers. Files still being written
 * are left out.
 */
fun listTripFiles(tripsDir: File): List<File> =
    tripsDir.listFiles { file -> file.isFile && !file.name.endsWith(PARTIAL_FILE_SUFFIX) }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()
