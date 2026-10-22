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

/**
 * The file [fileName] inside [tripsDir], or null when the name is not a plain file name. Names come
 * from the database; a corrupted row must never reach outside the trips folder.
 */
fun resolveTripFile(tripsDir: File, fileName: String): File? =
    if (fileName.isNotEmpty() && File(fileName).name == fileName) File(tripsDir, fileName) else null

/** All trip CSV files in [tripsDir], newest first. */
fun listTripFiles(tripsDir: File): List<File> =
    tripsDir.listFiles { file -> file.isFile && file.extension == TRIP_FILE_EXTENSION }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()
