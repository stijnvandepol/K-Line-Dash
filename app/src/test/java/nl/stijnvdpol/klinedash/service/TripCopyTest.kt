package nl.stijnvdpol.klinedash.service

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TripCopyTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class FakeDestination(
        val existing: MutableMap<String, Long> = HashMap(),
        private val failOn: Set<String> = emptySet(),
    ) : CopyDestination {
        val written = HashMap<String, ByteArray>()
        var listingFails = false

        override fun existingFiles(): Map<String, Long> {
            if (listingFails) throw IOException("unreadable")
            return existing
        }

        override fun writeNew(fileName: String, write: (OutputStream) -> Unit) {
            if (fileName in failOn) throw IOException("disk full")
            val out = ByteArrayOutputStream()
            write(out)
            written[fileName] = out.toByteArray()
        }
    }

    private fun file(name: String, text: String): File = folder.newFile(name).also { it.writeText(text) }

    @Test
    fun copiesFilesAndReportsProgress() = runBlocking {
        val destination = FakeDestination()
        val items = listOf(CopyItem("a.csv", file("a.csv", "aaa")), CopyItem("b.csv", file("b.csv", "bb")))

        val progress = copyTrips(items, destination).toList()

        assertEquals("aaa", String(destination.written.getValue("a.csv")))
        assertEquals("bb", String(destination.written.getValue("b.csv")))
        val last = progress.last()
        assertTrue(last.isFinished)
        assertEquals(2, last.copied)
        assertEquals(2, last.total)
        assertTrue(last.failures.isEmpty())
        assertFalse(progress.dropLast(1).any { it.isFinished })
    }

    @Test
    fun sameNameAndSizeIsSkippedAndTheRestStillCopied() = runBlocking {
        val destination = FakeDestination(existing = hashMapOf("a.csv" to 3L))
        val items = listOf(CopyItem("a.csv", file("a.csv", "aaa")), CopyItem("b.csv", file("b.csv", "bb")))

        val last = copyTrips(items, destination).toList().last()

        assertEquals(1, last.skipped)
        assertEquals(1, last.copied)
        assertFalse(destination.written.containsKey("a.csv"))
    }

    @Test
    fun failuresAreReportedPerFileWithoutStoppingTheOthers() = runBlocking {
        val destination = FakeDestination(failOn = setOf("bad.csv"))
        val items = listOf(
            CopyItem("bad.csv", file("bad.csv", "x")),
            CopyItem("gone.csv", File(folder.root, "gone.csv")),
            CopyItem("busy.csv", null, failure = "This trip is still recording."),
            CopyItem("ok.csv", file("ok.csv", "ok")),
        )

        val last = copyTrips(items, destination).toList().last()

        assertEquals(1, last.copied)
        assertEquals(listOf("bad.csv", "gone.csv", "busy.csv"), last.failures.map { it.fileName })
        assertEquals("This trip is still recording.", last.failures.last().reason)
    }

    @Test
    fun unreadableTargetFailsEveryFile() = runBlocking {
        val destination = FakeDestination().also { it.listingFails = true }
        val last = copyTrips(listOf(CopyItem("a.csv", file("a.csv", "a"))), destination).toList().last()
        assertTrue(last.isFinished)
        assertEquals(1, last.failures.size)
        assertEquals(0, last.copied)
    }
}
