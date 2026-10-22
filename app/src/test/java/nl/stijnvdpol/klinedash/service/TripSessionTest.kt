package nl.stijnvdpol.klinedash.service

import java.io.File
import nl.stijnvdpol.klinedash.core.obd.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TripSessionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val startMs = 100_000L

    private fun readings(now: Long, vararg ids: String) =
        ids.associateWith { Reading(it, 50.0, now) }

    private fun newSession(settleMs: Long = 20_000, flushEvery: Int = 10): TripSession =
        TripSession(File(folder.root, "t.csv"), startMs, settleMs = settleMs, flushEverySamples = flushEvery)

    private fun text() = File(folder.root, "t.csv").readText(Charsets.UTF_8)

    @Test
    fun fileIsUtf8WithByteOrderMark() {
        newSession().use { it.add(startMs, readings(startMs, "rpm")) }
        val bytes = File(folder.root, "t.csv").readBytes()
        assertEquals(listOf(0xEF, 0xBB, 0xBF), bytes.take(3).map { it.toInt() and 0xFF })
        assertTrue(text().removePrefix("\uFEFF").startsWith("time_iso,elapsed_s,"))
    }

    @Test
    fun headerWaitsForTheSettleTimeAndUsesEverySignalSeenUntilThen() {
        newSession().use { session ->
            session.add(startMs, readings(startMs, "rpm"))
            session.add(startMs + 10_000, readings(startMs + 10_000, "rpm", "battery"))
            assertEquals("nothing is on disk before the header", "", text())
            session.add(startMs + 20_000, readings(startMs + 20_000, "rpm", "battery", "load"))
            val header = text().lines().first()
            assertTrue(header, header.contains("rpm (rpm)") && header.contains("battery") && header.contains("load"))
            assertTrue("forced summary columns are always there", header.contains("speed") && header.contains("coolant"))
            assertEquals("header and the three kept samples", 4, text().lines().count { it.isNotEmpty() })
        }
    }

    @Test
    fun closingBeforeTheSettleTimeStillWritesTheKeptSamples() {
        val session = newSession()
        session.add(startMs, readings(startMs, "rpm"))
        session.add(startMs + 1_000, readings(startMs + 1_000, "rpm"))
        session.close()
        assertEquals(3, text().lines().count { it.isNotEmpty() })
        assertEquals(2, checkNotNull(session.summary()).sampleCount)
    }

    @Test
    fun rowsReachTheDiskEveryFlushInterval() {
        newSession(settleMs = 0, flushEvery = 3).use { session ->
            session.add(startMs, readings(startMs, "rpm")) // header + row, flushed
            val afterFirst = text().lines().count { it.isNotEmpty() }
            session.add(startMs + 1_000, readings(startMs + 1_000, "rpm"))
            session.add(startMs + 2_000, readings(startMs + 2_000, "rpm"))
            assertEquals("two rows still buffered", afterFirst, text().lines().count { it.isNotEmpty() })
            session.add(startMs + 3_000, readings(startMs + 3_000, "rpm"))
            assertEquals(afterFirst + 3, text().lines().count { it.isNotEmpty() })
        }
    }

    @Test
    fun summaryHasNoValueBeforeTheFirstSample() {
        newSession().use { assertEquals(null, it.summary()) }
    }
}
