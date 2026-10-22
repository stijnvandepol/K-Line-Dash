package nl.stijnvdpol.klinedash.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "TextExport"

/**
 * Where a text file the user asked for ends up: the document they picked with the Storage Access
 * Framework (no storage permission needed), or, on a head unit without a document picker, the
 * app's own folder, which a file manager or adb can reach. Shared by every "Export log" button.
 */
internal object TextExport {
    /** Writes [text] to the document at [uri]; false (and logged) when that fails. */
    suspend fun toUri(context: Context, uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: error("No output stream")
        }.onFailure { Log.w(TAG, "Export to $uri failed", it) }.isSuccess
    }

    /** Writes [text] to [fileName] in the app's own folder; the file, or null (logged) when that fails. */
    suspend fun toAppFolder(context: Context, fileName: String, text: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, fileName).also { it.writeText(text) }
        }.onFailure { Log.w(TAG, "Export to app folder ($fileName) failed", it) }.getOrNull()
    }
}
