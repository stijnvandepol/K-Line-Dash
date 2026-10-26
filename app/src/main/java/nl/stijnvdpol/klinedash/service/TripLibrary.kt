package nl.stijnvdpol.klinedash.service

import android.content.ContentResolver
import android.database.SQLException
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import nl.stijnvdpol.klinedash.data.MAX_KEEP_TRIPS
import nl.stijnvdpol.klinedash.data.Trip
import nl.stijnvdpol.klinedash.data.TripDao
import nl.stijnvdpol.klinedash.data.isLapSession

private const val TAG = "TripLibrary"

/**
 * The longest the list gets: as many plain trips as the limit allows ([MAX_KEEP_TRIPS]) plus room
 * for as many lap sessions, which retention never removes.
 */
private const val MAX_LISTED_TRIPS = 2 * MAX_KEEP_TRIPS

/** Trips removed per database round trip by [TripLibrary.prune]; the first run after an update may have thousands to do. */
private const val PRUNE_BATCH_SIZE = 100
private const val CSV_MIME_TYPE = "text/csv"
private const val GPX_MIME_TYPE = "application/gpx+xml"

/** Anything else (VBO) is plain text with no registered type; octet-stream keeps providers from renaming it. */
private const val BINARY_MIME_TYPE = "application/octet-stream"

/** The MIME type the folder picker's provider gets for [fileName]; a wrong one makes some providers append an extension. */
internal fun mimeTypeOf(fileName: String): String = when {
    fileName.endsWith(".csv") -> CSV_MIME_TYPE
    fileName.endsWith(".gpx") -> GPX_MIME_TYPE
    else -> BINARY_MIME_TYPE
}

/** Result of [TripLibrary.delete]. */
sealed interface DeleteResult {
    data object Deleted : DeleteResult

    /** The trip is being recorded right now and cannot be deleted. */
    data object IsRecording : DeleteResult
    data object NotFound : DeleteResult

    /** The file could not be removed; the trip is still listed. [reason] is a sentence for the user. */
    data class Failed(val reason: String) : DeleteResult
}

/** One trip that was not copied, with a sentence for the user. */
data class CopyFailure(val fileName: String, val reason: String)

/**
 * Progress of [TripLibrary.copyToTree]. The last emission has [isFinished] true; [failures] lists
 * every trip that was not copied, [skipped] those already on the target with the same size.
 */
data class CopyProgress(
    val total: Int,
    val copied: Int,
    val skipped: Int,
    val failures: List<CopyFailure>,
    val currentFileName: String?,
    val isFinished: Boolean,
)

/** Where [copyTrips] writes; the production implementation is a Storage Access Framework folder. */
internal interface CopyDestination {
    /** File name to size in bytes of what is already there. */
    fun existingFiles(): Map<String, Long>

    /** Creates [fileName] and lets [write] fill it; a file that failed half-way is removed again. */
    fun writeNew(fileName: String, write: (OutputStream) -> Unit)
}

/** One file to copy: [name] on the target, [source] on disk. */
internal class CopyItem(val name: String, val source: File?, val failure: String? = null)

/**
 * Copies [items] to [destination], emitting progress after each. A file with the same name and
 * size on the target counts as already copied (copying "all" twice would otherwise create
 * "name (1).csv" duplicates). Every error ends up in [CopyProgress.failures]; one failing file does
 * not stop the others.
 */
internal fun copyTrips(items: List<CopyItem>, destination: CopyDestination): Flow<CopyProgress> = flow {
    val existing = try {
        destination.existingFiles()
    } catch (e: IOException) {
        Log.e(TAG, "Could not read the target folder", e)
        emit(
            CopyProgress(
                items.size, 0, 0, items.map { CopyFailure(it.name, "The target folder cannot be read. Check the USB stick or SD card.") },
                null, true,
            ),
        )
        return@flow
    }
    var copied = 0
    var skipped = 0
    val failures = ArrayList<CopyFailure>()
    for (item in items) {
        emit(CopyProgress(items.size, copied, skipped, failures.toList(), item.name, false))
        val source = item.source
        when {
            item.failure != null -> failures += CopyFailure(item.name, item.failure)
            source == null || !source.isFile -> failures += CopyFailure(item.name, "The trip file is missing.")
            existing[item.name] == source.length() -> skipped++
            else -> try {
                destination.writeNew(item.name) { out -> source.inputStream().use { it.copyTo(out) } }
                copied++
            } catch (e: IOException) {
                Log.e(TAG, "Copying ${item.name} failed", e)
                failures += CopyFailure(item.name, "Could not write the file. Check the free space of the USB stick or SD card.")
            }
        }
    }
    emit(CopyProgress(items.size, copied, skipped, failures.toList(), null, true))
}

/** A folder chosen with the system folder picker (ACTION_OPEN_DOCUMENT_TREE), written via [DocumentsContract]. */
internal class SafDestination(private val resolver: ContentResolver, treeUri: Uri) : CopyDestination {
    private val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    private val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
    private val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)

    override fun existingFiles(): Map<String, Long> {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        return resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getLong(1))
            }
        } ?: throw IOException("The target folder returned no listing")
    }

    override fun writeNew(fileName: String, write: (OutputStream) -> Unit) {
        val document = DocumentsContract.createDocument(resolver, parentUri, mimeTypeOf(fileName), fileName)
            ?: throw IOException("Could not create $fileName")
        try {
            val out = resolver.openOutputStream(document) ?: throw IOException("Could not open $fileName")
            out.use(write)
        } catch (e: IOException) {
            removeQuietly(document)
            throw e
        }
    }

    /** The original error is what gets reported; a leftover empty file is only logged. */
    private fun removeQuietly(document: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, document)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Could not remove the incomplete file", e)
        }
    }
}

/**
 * Trip management for the UI: the list ([trips], newest first, at most [MAX_LISTED_TRIPS]),
 * [delete] (with the generated PC files of the trip), [prune] (the retention limit) and
 * [copyToTree] (to a USB stick or SD card chosen with the system folder picker; the picker grants
 * the app write access, no permission needed).
 */
class TripLibrary internal constructor(
    private val tripDao: TripDao,
    private val tripsDir: File,
    private val recordingState: StateFlow<RecordingState>,
    private val resolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher,
) {
    constructor(
        tripDao: TripDao,
        tripsDir: File,
        recordingState: StateFlow<RecordingState>,
        resolver: ContentResolver,
    ) : this(tripDao, tripsDir, recordingState, resolver, Dispatchers.IO)

    val trips: Flow<List<Trip>> = tripDao.observeNewest(MAX_LISTED_TRIPS)

    /** Removes the row and the file; refused for the trip being recorded. */
    suspend fun delete(tripId: Long): DeleteResult {
        val trip = tripDao.get(tripId) ?: return DeleteResult.NotFound
        return remove(trip)
    }

    /**
     * Retention: removes the finished trips beyond the newest [keep], oldest first, each with its
     * generated files. Lap sessions are not counted and not removed (see [isLapSession]), and neither
     * is a trip without an end time (recording, or not recovered yet). A trip whose file cannot be
     * removed stays, so no file is orphaned, and is tried again by the next run. Returns how many
     * trips were removed. A database error is logged and ends the run; it never reaches the caller.
     */
    suspend fun prune(keep: Int): Int {
        require(keep >= 0) { "keep must not be negative" }
        var removed = 0
        try {
            do {
                val batch = tripDao.getBeyondRetention(keep, PRUNE_BATCH_SIZE)
                val removedInBatch = batch.count { remove(it) == DeleteResult.Deleted }
                removed += removedInBatch
            } while (batch.size == PRUNE_BATCH_SIZE && removedInBatch > 0)
        } catch (e: SQLException) {
            Log.e(TAG, "Removing old trips failed after $removed", e)
        }
        if (removed > 0) Log.i(TAG, "Removed $removed trips beyond the newest $keep")
        return removed
    }

    private suspend fun remove(trip: Trip): DeleteResult {
        if (isRecording(trip)) return DeleteResult.IsRecording
        val file = resolveTripFile(tripsDir, trip.fileName)
        // Existing but undeletable means the row must stay: otherwise the file would be orphaned.
        // The generated exports (vbo, gpx, ...) go with it.
        val removed = file == null || withContext(ioDispatcher) {
            val files = derivedFiles(tripsDir, trip.fileName) + leftoverPartialFiles(tripsDir, trip.fileName) + file
            files.all { !it.exists() || it.delete() }
        }
        if (!removed) {
            Log.w(TAG, "Could not delete the files of trip ${trip.id} (${trip.fileName}), keeping its row")
            return DeleteResult.Failed("Could not delete the trip file. Try again.")
        }
        tripDao.deleteById(trip.id)
        return DeleteResult.Deleted
    }

    /**
     * Copies the CSV files of [tripIds] (null: every finished trip of the list, the newest
     * [MAX_LISTED_TRIPS]) into the folder [treeUri]
     * chosen by the user. A selected trip that is still recording is reported as a failure;
     * with null it is left out. With [includeExports] the PC files that were generated for these
     * trips (see [TripExporter]; only those that exist) are copied too, so [CopyProgress.total]
     * counts files, not trips.
     */
    fun copyToTree(treeUri: Uri, tripIds: Set<Long>? = null, includeExports: Boolean = true): Flow<CopyProgress> = flow {
        val all = tripDao.getNewest(MAX_LISTED_TRIPS)
        val selected = if (tripIds == null) all.filterNot(::isRecording) else all.filter { it.id in tripIds }
        val items = selected.flatMap { trip ->
            when {
                isRecording(trip) -> listOf(CopyItem(trip.fileName, null, "This trip is still recording."))
                else -> listOf(CopyItem(trip.fileName, resolveTripFile(tripsDir, trip.fileName))) +
                    if (includeExports) derivedFiles(tripsDir, trip.fileName).map { CopyItem(it.name, it) } else emptyList()
            }
        }
        emitAll(copyTrips(items, SafDestination(resolver, treeUri)))
    }.flowOn(ioDispatcher)

    private fun isRecording(trip: Trip): Boolean =
        (recordingState.value as? RecordingState.Recording)?.fileName == trip.fileName
}
