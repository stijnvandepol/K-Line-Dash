package nl.stijnvdpol.klinedash.core.log

import java.util.TimeZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RingCommLogTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `keeps only the newest entries when full`() {
        val log = RingCommLog(capacity = 3, clock = { 0L }, timeZone = utc)
        for (i in 1..5) log.record(LogKind.TX, "cmd$i")
        assertEquals(listOf("cmd3", "cmd4", "cmd5"), log.snapshot().map { it.text })
    }

    @Test
    fun `clear empties the log`() {
        val log = RingCommLog(clock = { 0L }, timeZone = utc)
        log.record(LogKind.INFO, "x")
        log.clear()
        assertEquals(0, log.snapshot().size)
    }

    @Test
    fun `export format shows time kind and escaped crlf`() {
        var now = 3_723_004L // 01:02:03.004 UTC
        val log = RingCommLog(clock = { now }, timeZone = utc)
        log.record(LogKind.TX, "010C")
        now += 1_000
        log.record(LogKind.RX, "41 0C 1A F8 \r\r")
        log.record(LogKind.ERROR, "a\nb")
        assertEquals(
            "01:02:03.004 TX 010C\n" +
                "01:02:04.004 RX 41 0C 1A F8 \\r\\r\n" +
                "01:02:04.004 ERROR a\\nb\n",
            log.exportText(),
        )
    }

    @Test
    fun `entries flow emits new records`() = runTest(UnconfinedTestDispatcher()) {
        val log = RingCommLog(clock = { 5L }, timeZone = utc)
        val received = mutableListOf<LogEntry>()
        val job = launch { log.entries.toList(received) }
        log.record(LogKind.TX, "ATZ")
        log.record(LogKind.RX, "OK")
        assertEquals(listOf("ATZ", "OK"), received.map { it.text })
        assertEquals(listOf(LogKind.TX, LogKind.RX), received.map { it.kind })
        job.cancel()
    }
}
