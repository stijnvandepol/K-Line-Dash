package nl.stijnvdpol.klinedash.service

import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import kotlinx.coroutines.CoroutineExceptionHandler
import nl.stijnvdpol.klinedash.BuildConfig

private const val TAG = "CrashLog"

/** Reports kept on disk; recording a new one removes the oldest beyond this. */
const val MAX_CRASH_REPORTS = 10

/** A report file never grows past this, so a runaway stack trace cannot fill the storage. */
const val MAX_CRASH_FILE_BYTES = 64 * 1024

private const val MAX_HEADER_VALUE_CHARS = 500
private const val FILE_PREFIX = "crash-"
private const val FILE_SUFFIX = ".txt"
private const val FILE_MARKER = "KLineDash-Crash 1"
private const val STACK_TRACE_KEY = "stacktrace:"
private const val TRUNCATED_NOTE = "\n[truncated]\n"
private val FILE_NAME = Regex("""crash-\d{15}\.txt""")

/** One recorded uncaught exception. */
data class CrashReport(
    val timeMs: Long,
    val threadName: String,
    /** Exception class and message on one line. */
    val summary: String,
    val stackTrace: String,
    val appVersion: String,
)

/**
 * Local crash log, no cloud: every uncaught exception is written to a small text file in [dir] so the
 * app can offer it to the user to send along with a bug report. Keeps at most [MAX_CRASH_REPORTS]
 * files of [MAX_CRASH_FILE_BYTES] each.
 *
 * All methods do blocking file I/O; call them off the main thread, except [record] which runs on
 * whatever thread crashed. In a release build the stack trace is obfuscated; decode it with the
 * mapping.txt published next to the APK.
 */
class CrashLog(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val appVersion: String = BuildConfig.VERSION_NAME,
) {
    /** Writes [error] synchronously. Throws if the file cannot be written; [asUncaughtExceptionHandler] handles that. */
    fun record(thread: Thread, error: Throwable) {
        val now = clock()
        val report = CrashReport(
            timeMs = now,
            threadName = oneLine(thread.name),
            summary = oneLine("${error.javaClass.name}: ${error.message}"),
            stackTrace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString(),
            appVersion = oneLine(appVersion),
        )
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create the crash log folder" }
        fileFor(now).writeBytes(encode(report))
        reportFiles().drop(MAX_CRASH_REPORTS).forEach { file ->
            if (!file.delete()) Log.w(TAG, "Could not remove old crash report ${file.name}")
        }
    }

    /** The newest readable report, or null when there is none. */
    fun latest(): CrashReport? = reportFiles().asSequence().mapNotNull(::read).firstOrNull()

    /** Every readable report, newest first. */
    fun all(): List<CrashReport> = reportFiles().mapNotNull(::read)

    fun clear() {
        reportFiles().forEach { file ->
            if (!file.delete()) Log.w(TAG, "Could not remove crash report ${file.name}")
        }
    }

    /** All reports as one plain text, newest first, ready to share. Empty when there are none. */
    fun exportText(): String = all().joinToString("\n\n") { report ->
        buildString {
            appendLine("K-Line Dash ${report.appVersion} crashed at ${Instant.ofEpochMilli(report.timeMs)}")
            appendLine("Thread: ${report.threadName}")
            appendLine(report.summary)
            append(report.stackTrace.trimEnd())
        }
    }

    /**
     * An uncaught-exception handler that records the crash and then hands it to [previous], so Android
     * still shows its crash dialog and ends the process. A failing write must not stop that, hence
     * Throwable: an OutOfMemoryError while crashing is exactly the case that has to be survived.
     */
    fun asUncaughtExceptionHandler(previous: Thread.UncaughtExceptionHandler?): Thread.UncaughtExceptionHandler =
        Thread.UncaughtExceptionHandler { thread, error ->
            try {
                record(thread, error)
            } catch (writeFailure: Throwable) {
                Log.e(TAG, "Could not write the crash log", writeFailure)
            }
            previous?.uncaughtException(thread, error)
        }

    /**
     * For the scopes of long-running work (service, application): an exception no coroutine caught is
     * logged under [tag] and recorded here, and only the coroutine it escaped from ends, not the
     * process. Installed on the root launch (or the scope), as a handler on a child is ignored.
     */
    fun asCoroutineExceptionHandler(tag: String): CoroutineExceptionHandler = CoroutineExceptionHandler { _, error ->
        Log.e(tag, "Uncaught error in a coroutine, the app carries on without it", error)
        asUncaughtExceptionHandler(previous = null).uncaughtException(Thread.currentThread(), error)
    }

    /** Newest first: the zero-padded time in the name makes name order equal time order. */
    private fun reportFiles(): List<File> =
        dir.listFiles { _, name -> FILE_NAME.matches(name) }.orEmpty().sortedByDescending { it.name }

    private fun fileFor(timeMs: Long): File {
        var stamp = timeMs
        var file = File(dir, "$FILE_PREFIX${stamp.toString().padStart(15, '0')}$FILE_SUFFIX")
        while (file.exists()) {
            stamp++
            file = File(dir, "$FILE_PREFIX${stamp.toString().padStart(15, '0')}$FILE_SUFFIX")
        }
        return file
    }

    private fun read(file: File): CrashReport? {
        if (file.length() > MAX_CRASH_FILE_BYTES) {
            Log.w(TAG, "Ignoring crash report ${file.name}: larger than $MAX_CRASH_FILE_BYTES bytes")
            return null
        }
        val report = try {
            parse(file.readText(Charsets.UTF_8))
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Ignoring crash report ${file.name}: cannot be read", e)
            return null
        }
        if (report == null) Log.w(TAG, "Ignoring crash report ${file.name}: not a crash report")
        return report
    }
}

private fun oneLine(text: String): String = text.replace('\n', ' ').replace('\r', ' ').take(MAX_HEADER_VALUE_CHARS)

private fun encode(report: CrashReport): ByteArray {
    val text = buildString {
        appendLine(FILE_MARKER)
        appendLine("time: ${report.timeMs}")
        appendLine("thread: ${report.threadName}")
        appendLine("version: ${report.appVersion}")
        appendLine("summary: ${report.summary}")
        appendLine(STACK_TRACE_KEY)
        append(report.stackTrace)
    }
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= MAX_CRASH_FILE_BYTES) return bytes
    val note = TRUNCATED_NOTE.toByteArray(Charsets.UTF_8)
    return bytes.copyOf(MAX_CRASH_FILE_BYTES - note.size) + note
}

/** Null when [text] is not a report this class wrote. */
private fun parse(text: String): CrashReport? {
    val lines = text.lines()
    if (lines.firstOrNull() != FILE_MARKER) return null
    val stackIndex = lines.indexOf(STACK_TRACE_KEY)
    if (stackIndex < 0) return null
    val header = lines.subList(1, stackIndex).associate { line ->
        line.substringBefore(": ", "") to line.substringAfter(": ", "")
    }
    val timeMs = header["time"]?.toLongOrNull() ?: return null
    return CrashReport(
        timeMs = timeMs,
        threadName = header["thread"] ?: return null,
        summary = header["summary"] ?: return null,
        stackTrace = lines.drop(stackIndex + 1).joinToString("\n"),
        appVersion = header["version"] ?: return null,
    )
}
