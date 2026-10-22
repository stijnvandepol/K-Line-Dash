package nl.stijnvdpol.klinedash.ui.help

import android.annotation.SuppressLint
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.stijnvdpol.klinedash.AppGraph
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.service.CrashLog
import nl.stijnvdpol.klinedash.service.CrashReport
import nl.stijnvdpol.klinedash.ui.TextExport
import nl.stijnvdpol.klinedash.ui.TimedMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val NOTICE_MS = 8_000L

/**
 * SETUP > About: the crash reports the app wrote after an unexpected stop, and what the user does
 * with them (export, clear). The files are read off the main thread each time the page opens.
 */
internal class AboutViewModel(graph: AppGraph) : ViewModel() {
    // The application context: it lives as long as the process, so holding it leaks nothing.
    @SuppressLint("StaticFieldLeak")
    private val context = graph.appContext
    private val crashLog: CrashLog = graph.crashLog

    private val _crashes = MutableStateFlow<List<CrashReport>?>(null)

    /** Newest first; null until the files are read. */
    val crashes: StateFlow<List<CrashReport>?> = _crashes.asStateFlow()

    private val message = TimedMessage(viewModelScope, NOTICE_MS)

    /** The result of the last export or clear, for a few seconds. */
    val notice: StateFlow<String?> = message.text

    fun reload() {
        viewModelScope.launch { _crashes.value = withContext(Dispatchers.IO) { crashLog.all() } }
    }

    fun exportFileName(): String = "klinedash-crash-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"

    /** Writes all reports to a document the user picked with the Storage Access Framework. */
    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { crashLog.exportText() }
            val isOk = TextExport.toUri(context, uri, text)
            message.show(context.getString(if (isOk) R.string.about_crash_exported else R.string.about_crash_export_failed))
        }
    }

    /** For a head unit without a document picker: the app's own folder, which a file manager or adb can reach. */
    fun exportToAppFolder(fileName: String) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { crashLog.exportText() }
            val file = TextExport.toAppFolder(context, fileName, text)
            message.show(
                if (file == null) context.getString(R.string.about_crash_export_failed)
                else context.getString(R.string.about_crash_saved_to, file.absolutePath),
            )
        }
    }

    fun clear() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { crashLog.clear() }
            _crashes.value = emptyList()
            message.show(context.getString(R.string.about_crash_cleared))
        }
    }

    companion object {
        fun forGraph(graph: AppGraph) = AboutViewModel(graph)
    }
}
