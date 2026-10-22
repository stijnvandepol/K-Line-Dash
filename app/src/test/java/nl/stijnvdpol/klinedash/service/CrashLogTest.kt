package nl.stijnvdpol.klinedash.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashLogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_792_677_787_000L
    private val dir get() = File(folder.root, "crashes")
    private val log by lazy { CrashLog(dir, clock = { now }, appVersion = "1.2.3") }

    private fun crash(message: String = "boom", threadName: String = "worker-1") {
        log.record(Thread(threadName), IllegalStateException(message))
        now += 1_000
    }

    @Test
    fun emptyLogHasNoReports() {
        assertNull(log.latest())
        assertTrue(log.all().isEmpty())
        assertEquals("", log.exportText())
    }

    @Test
    fun recordedCrashIsReadBackWithAllFields() {
        log.record(Thread("main"), IllegalStateException("boom"))
        val report = checkNotNull(log.latest())
        assertEquals(1_792_677_787_000L, report.timeMs)
        assertEquals("main", report.threadName)
        assertEquals("java.lang.IllegalStateException: boom", report.summary)
        assertEquals("1.2.3", report.appVersion)
        assertTrue(report.stackTrace.startsWith("java.lang.IllegalStateException: boom"))
        assertTrue(report.stackTrace.contains("CrashLogTest"))
    }

    @Test
    fun reportsComeBackNewestFirst() {
        crash("first")
        crash("second")
        crash("third")
        assertEquals(listOf("third", "second", "first"), log.all().map { it.summary.substringAfter(": ") })
        assertEquals("third", checkNotNull(log.latest()).summary.substringAfter(": "))
    }

    @Test
    fun onlyTheNewestTenAreKept() {
        repeat(MAX_CRASH_REPORTS + 3) { crash("crash $it") }
        val reports = log.all()
        assertEquals(MAX_CRASH_REPORTS, reports.size)
        assertEquals("crash 12", reports.first().summary.substringAfter(": "))
        assertEquals("crash 3", reports.last().summary.substringAfter(": "))
        assertEquals(MAX_CRASH_REPORTS, dir.listFiles()!!.size)
    }

    @Test
    fun hugeStackTraceStaysWithinTheFileLimit() {
        crash("x".repeat(1_000_000))
        val error = Exception("deep")
        error.stackTrace = Array(5_000) { StackTraceElement("com.example.Deep$it", "call", "Deep.kt", it) }
        log.record(Thread("main"), error)
        val files = dir.listFiles()!!
        assertTrue(files.all { it.length() <= MAX_CRASH_FILE_BYTES })
        val report = checkNotNull(log.latest())
        assertEquals("java.lang.Exception: deep", report.summary)
        assertTrue(report.stackTrace.endsWith("[truncated]\n"))
    }

    @Test
    fun multiLineMessageStaysOnOneSummaryLine() {
        crash("line one\nline two")
        assertEquals("java.lang.IllegalStateException: line one line two", checkNotNull(log.latest()).summary)
    }

    @Test
    fun corruptAndUnrelatedFilesAreIgnored() {
        crash("good")
        File(dir, "crash-000000000000001.txt").writeText("not a crash report at all")
        File(dir, "crash-000000000000002.txt").writeText("KLineDash-Crash 1\ntime: soon\nstacktrace:\n")
        File(dir, "crash-000000000000003.txt").writeBytes(ByteArray(MAX_CRASH_FILE_BYTES + 1))
        File(dir, "notes.txt").writeText("unrelated")
        val reports = log.all()
        assertEquals(1, reports.size)
        assertEquals("java.lang.IllegalStateException: good", reports.single().summary)
    }

    @Test
    fun clearRemovesReportsButNotOtherFiles() {
        crash()
        crash()
        File(dir, "notes.txt").writeText("unrelated")
        log.clear()
        assertNull(log.latest())
        assertEquals(listOf("notes.txt"), dir.list()!!.toList())
    }

    @Test
    fun twoCrashesInTheSameMillisecondAreBothKept() {
        log.record(Thread("a"), Exception("one"))
        log.record(Thread("b"), Exception("two"))
        assertEquals(listOf("two", "one"), log.all().map { it.summary.substringAfter(": ") })
    }

    @Test
    fun exportTextListsNewestFirstWithVersionAndThread() {
        crash("first", threadName = "alpha")
        crash("second", threadName = "beta")
        val text = log.exportText()
        assertTrue(text.contains("K-Line Dash 1.2.3 crashed at 2026-"))
        assertTrue(text.contains("Thread: beta"))
        assertTrue(text.indexOf("second") < text.indexOf("first"))
    }

    @Test
    fun handlerRecordsAndThenChainsToThePreviousHandler() {
        var chained: Throwable? = null
        val handler = log.asUncaughtExceptionHandler { _, error -> chained = error }
        val error = IllegalStateException("fatal")
        handler.uncaughtException(Thread("main"), error)
        assertEquals("java.lang.IllegalStateException: fatal", checkNotNull(log.latest()).summary)
        assertEquals(error, chained)
    }

    @Test
    fun handlerStillChainsWhenTheLogCannotBeWritten() {
        val blocked = File(folder.root, "blocked").also { it.writeText("a file, not a folder") }
        var chained: Throwable? = null
        val handler = CrashLog(blocked, clock = { now }, appVersion = "1.2.3")
            .asUncaughtExceptionHandler { _, error -> chained = error }
        val error = IllegalStateException("fatal")
        handler.uncaughtException(Thread("main"), error)
        assertEquals(error, chained)
    }

    @Test
    fun handlerWithoutAPreviousHandlerDoesNotThrow() {
        log.asUncaughtExceptionHandler(null).uncaughtException(Thread("main"), Exception("fatal"))
        assertEquals(1, log.all().size)
    }
}
