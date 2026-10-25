package nl.stijnvdpol.klinedash.core.export

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val COPY_BUFFER_BYTES = 16 * 1024
internal const val MAX_DOWNLOAD_NAME_LENGTH = 100
private const val FALLBACK_DOWNLOAD_NAME = "trip.csv"

/** The offered files in a stable order; a download link carries the index and the name to detect a changed list. */
internal fun listOfferedFiles(source: TripFileSource): List<File> =
    source.files()
        .filter { it.isFile && it.canRead() }
        .sortedWith(compareBy<File> { it.name }.thenBy { it.path })

/**
 * Reduces [name] to an allow-list of safe characters, which makes it safe inside a quoted
 * Content-Disposition value and as a ZIP entry name (no separators, no dot-dot, no quotes).
 */
internal fun sanitizeDownloadName(name: String): String {
    val cleaned = name.map { if ((it.isLetterOrDigit() && it.code < 0x80) || it == '.' || it == '-' || it == '_') it else '_' }
        .joinToString("")
        .trimStart('.')
        .take(MAX_DOWNLOAD_NAME_LENGTH)
    return cleaned.ifEmpty { FALLBACK_DOWNLOAD_NAME }
}

/** Streams [input] to [out], at most [length] bytes so a file that is still growing cannot overrun. */
internal fun streamBounded(input: InputStream, out: OutputStream, length: Long) {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var remaining = length
    while (remaining > 0) {
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (count < 0) return
        out.write(buffer, 0, count)
        remaining -= count
    }
}

/** Streams every file as a ZIP, one entry per file, never holding a file fully in memory. */
internal fun streamZip(files: List<File>, out: OutputStream) {
    val usedNames = HashSet<String>()
    ZipOutputStream(out).use { zip ->
        for (file in files) {
            val length = file.length()
            zip.putNextEntry(ZipEntry(uniqueEntryName(sanitizeDownloadName(file.name), usedNames)))
            FileInputStream(file).use { streamBounded(it, zip, length) }
            zip.closeEntry()
        }
    }
}

private fun uniqueEntryName(base: String, used: MutableSet<String>): String {
    var candidate = base
    var counter = 1
    while (!used.add(candidate)) candidate = "${counter++}_$base"
    return candidate
}
