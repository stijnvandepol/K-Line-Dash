package nl.stijnvdpol.klinedash.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.stijnvdpol.klinedash.AppGraph
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.elm.ElmLink
import nl.stijnvdpol.klinedash.core.elm.ElmResult
import nl.stijnvdpol.klinedash.core.log.LogEntry
import nl.stijnvdpol.klinedash.core.log.LogKind
import nl.stijnvdpol.klinedash.service.ObdServiceState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale

/** One rendered log line; text is precomputed so scrolling does no string work. */
data class LogLine(val id: Long, val time: String, val kind: LogKind, val text: String)

/** Outcome of a manually sent command. [type] is the classified [ElmResult] name. */
data class CommandReply(val command: String, val type: String, val text: String, val isData: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class DebugViewModel(graph: AppGraph) : ViewModel() {
    // The application context: it lives as long as the process, so holding it leaks nothing.
    @SuppressLint("StaticFieldLeak")
    private val context = graph.appContext

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _autoScroll = MutableStateFlow(true)
    val autoScroll: StateFlow<Boolean> = _autoScroll.asStateFlow()

    private val _reply = MutableStateFlow<CommandReply?>(null)
    val reply: StateFlow<CommandReply?> = _reply.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val clearTicks = MutableStateFlow(0)
    private var noticeJob: Job? = null

    /**
     * Log lines for the debug screen. WhileSubscribed: the upstream (and its buffering work) only
     * runs while the screen is visible; on return it is rebuilt from the link's ring buffer.
     */
    val lines: StateFlow<List<LogLine>> = ObdServiceState.link
        .flatMapLatest { link -> if (link == null) flowOf(emptyList<LogLine>()) else logLines(link) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), emptyList())

    val link: StateFlow<ElmLink?> = ObdServiceState.link

    val connectionState: StateFlow<ConnectionState> = ObdServiceState.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), ConnectionState.Idle)

    /**
     * Collects entries into a bounded buffer and publishes an immutable snapshot at most every
     * [FLUSH_MS]. Batching keeps recomposition to ~10 Hz however fast the adapter chatters, which
     * matters on a weak head-unit CPU. All children run on the collector's (main) dispatcher, so
     * the plain variables below need no locking.
     */
    private fun logLines(link: ElmLink): Flow<List<LogLine>> = channelFlow {
        val buffer = ArrayDeque<LogLine>()
        val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        var nextId = 0L
        var dirty = false

        fun add(entry: LogEntry) {
            buffer.addLast(
                LogLine(nextId++, timeFormat.format(Date(entry.timestampMs)), entry.kind, renderLogText(entry.kind, entry.text)),
            )
            if (buffer.size > MAX_LINES) buffer.removeFirst()
            dirty = true
        }

        launch {
            val log = link.log
            // Entries recorded between registering the subscription and taking the snapshot are
            // in both. They are the very same instances and arrive first on the live flow, so live
            // entries are skipped while they match the snapshot tail (identity, not equals).
            val overlap: MutableSet<LogEntry> = Collections.newSetFromMap(IdentityHashMap())
            log.entries
                .onSubscription {
                    val history = log.snapshot()
                    overlap.addAll(history.takeLast(OVERLAP_CHECK))
                    // Only what the screen keeps: rendering 5000 lines on a weak CPU is a visible stall.
                    history.takeLast(MAX_LINES).forEach(::add)
                }
                .collect { entry ->
                    if (overlap.isNotEmpty()) {
                        if (overlap.remove(entry)) return@collect
                        overlap.clear()
                    }
                    add(entry)
                }
        }
        launch {
            while (true) {
                delay(FLUSH_MS)
                if (dirty && !_paused.value) {
                    dirty = false
                    send(buffer.toList())
                }
            }
        }
        launch {
            // drop(1): the StateFlow replays its current value on subscribe, which is not a clear request.
            clearTicks.drop(1).collect {
                buffer.clear()
                dirty = false
                send(emptyList())
            }
        }
    }

    fun setPaused(paused: Boolean) {
        _paused.value = paused
    }

    fun setAutoScroll(enabled: Boolean) {
        _autoScroll.value = enabled
    }

    fun clear() {
        ObdServiceState.link.value?.log?.clear()
        clearTicks.update { it + 1 }
    }

    fun send(rawCommand: String) {
        val command = rawCommand.trim().uppercase(Locale.ROOT)
        if (command.isEmpty() || _sending.value) return
        val link = ObdServiceState.link.value
        if (link == null) {
            _reply.value = notConnectedReply(command)
            return
        }
        viewModelScope.launch {
            _sending.value = true
            _reply.value = try {
                link.execute(command).toReply(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                notConnectedReply(command)
            } catch (e: Exception) {
                CommandReply(command, context.getString(R.string.debug_reply_error), e.message ?: e.javaClass.simpleName, false)
            }
            _sending.value = false
        }
    }

    fun exportFileName(): String =
        "klinedash-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"

    /** Writes the log to a document the user picked with the Storage Access Framework. */
    fun exportTo(uri: Uri) {
        val link = ObdServiceState.link.value
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = link?.log?.exportText().orEmpty()
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("No output stream")
                }.onFailure { Log.w(TAG, "Log export to $uri failed", it) }.isSuccess
            }
            showNotice(context.getString(if (ok) R.string.debug_export_ok else R.string.debug_export_failed))
        }
    }

    /**
     * Fallback for head units without a document picker: app-specific external storage needs no
     * permission and can be pulled with adb or a file manager.
     */
    fun exportToAppFolder(fileName: String, pickerMissing: Boolean) {
        val link = ObdServiceState.link.value
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = context.getExternalFilesDir(null) ?: context.filesDir
                    File(dir, fileName).also { it.writeText(link?.log?.exportText().orEmpty()) }
                }.onFailure { Log.w(TAG, "Log export to app folder ($fileName) failed", it) }.getOrNull()
            }
            val message = when {
                file == null -> context.getString(R.string.debug_export_failed)
                pickerMissing -> context.getString(R.string.debug_export_no_picker, file.absolutePath)
                else -> context.getString(R.string.debug_export_saved_to, file.absolutePath)
            }
            showNotice(message)
        }
    }

    private fun notConnectedReply(command: String) =
        CommandReply(command, context.getString(R.string.debug_reply_error), context.getString(R.string.debug_not_connected), false)

    private fun showNotice(text: String) {
        _notice.value = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _notice.value = null
        }
    }

    private companion object {
        const val TAG = "DebugViewModel"
        const val MAX_LINES = 2_000

        /** Far more entries than can be recorded between subscribing and taking the snapshot. */
        const val OVERLAP_CHECK = 512
        const val FLUSH_MS = 100L
        const val NOTICE_MS = 8_000L
    }
}

/** Makes control characters visible: CR as the return symbol, LF as a literal \n. */
internal fun String.withVisibleControlChars(): String =
    replace("\r", "⏎").replace("\n", "\\n")

private fun renderLogText(kind: LogKind, text: String): String {
    val prefix = when (kind) {
        LogKind.TX -> "> "
        LogKind.RX -> "< "
        LogKind.ERROR -> "! "
        LogKind.INFO -> "# "
    }
    return prefix + text.withVisibleControlChars()
}

private fun ElmResult.toReply(command: String): CommandReply {
    val type = when (this) {
        is ElmResult.Data -> "DATA"
        is ElmResult.NoData -> "NO DATA"
        is ElmResult.Unknown -> "UNKNOWN (?)"
        is ElmResult.InitError -> "INIT ERROR"
        is ElmResult.BusError -> "BUS ERROR"
        is ElmResult.CanError -> "CAN ERROR"
        is ElmResult.Stopped -> "STOPPED"
        is ElmResult.BufferFull -> "BUFFER FULL"
        is ElmResult.Timeout -> "TIMEOUT"
        is ElmResult.Garbage -> "GARBAGE"
    }
    val text = if (this is ElmResult.Data) lines.joinToString("  |  ") else raw.trim().withVisibleControlChars()
    return CommandReply(command, type, text, isData = this is ElmResult.Data)
}
