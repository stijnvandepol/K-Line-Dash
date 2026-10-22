package nl.stijnvdpol.klinedash.ui.pages

import nl.stijnvdpol.klinedash.core.obd.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalHistoryTest {
    @Test
    fun `points come back oldest first`() {
        val history = SignalHistory(4)
        history.append(10, 1.0)
        history.append(20, 2.0)
        assertEquals(2, history.size)
        assertEquals(10L, history.timeAt(0))
        assertEquals(2.0, history.valueAt(1), 0.0)
    }

    @Test
    fun `the oldest point is overwritten when the buffer is full`() {
        val history = SignalHistory(3)
        (1..5).forEach { history.append(it * 10L, it.toDouble()) }
        assertEquals(3, history.size)
        assertEquals(listOf(30L, 40L, 50L), (0 until history.size).map { history.timeAt(it) })
        assertEquals(listOf(3.0, 4.0, 5.0), (0 until history.size).map { history.valueAt(it) })
    }

    @Test
    fun `a point that is not newer than the last one is ignored`() {
        val history = SignalHistory(3)
        assertTrue(history.append(10, 1.0))
        assertFalse(history.append(10, 9.0))
        assertFalse(history.append(5, 9.0))
        assertEquals(1, history.size)
        assertEquals(1.0, history.valueAt(0), 0.0)
    }

    @Test
    fun `finds the first point inside a window, also after the buffer wrapped`() {
        val history = SignalHistory(4)
        (1..6).forEach { history.append(it * 1_000L, it.toDouble()) }
        assertEquals(0, history.firstIndexAtOrAfter(0))
        assertEquals(1, history.firstIndexAtOrAfter(4_000))
        assertEquals(2, history.firstIndexAtOrAfter(4_001))
        assertEquals(history.size, history.firstIndexAtOrAfter(7_000))
    }

    @Test
    fun `the range only covers the points inside the window`() {
        val history = SignalHistory(10)
        history.append(1_000, 100.0)
        history.append(70_000, 5.0)
        history.append(80_000, 9.0)
        assertEquals(ValueRange(5.0, 9.0), history.rangeSince(80_000 - GRAPH_WINDOW_MS))
        assertNull(history.rangeSince(90_000))
    }

    @Test
    fun `the default capacity holds a minute of readings at 10 a second`() {
        assertTrue(HISTORY_CAPACITY >= GRAPH_WINDOW_MS / 100)
    }
}

class GraphHistoryTest {
    private fun reading(id: String, value: Double, atMs: Long) = Reading(id, value, atMs)

    @Test
    fun `records only the signals that have a graph`() {
        val history = GraphHistory()
        val added = history.record(setOf("rpm"), mapOf("rpm" to reading("rpm", 900.0, 10), "speed" to reading("speed", 30.0, 10)))
        assertTrue(added)
        assertEquals(1, history.of("rpm")?.size)
        assertNull(history.of("speed"))
    }

    @Test
    fun `seeing the same reading again adds nothing`() {
        val history = GraphHistory()
        val readings = mapOf("rpm" to reading("rpm", 900.0, 10))
        assertTrue(history.record(setOf("rpm"), readings))
        assertFalse(history.record(setOf("rpm"), readings))
        assertEquals(1, history.of("rpm")?.size)
    }

    @Test
    fun `a signal without a reading yet is skipped`() {
        val history = GraphHistory()
        assertFalse(history.record(setOf("rpm", "coolant"), emptyMap()))
        assertNull(history.of("coolant"))
    }
}

class PlotRangeTest {
    @Test
    fun `a range wider than the minimum is used as it is`() {
        assertEquals(ValueRange(10.0, 50.0), plotRange(ValueRange(10.0, 50.0), minSpan = 5.0))
    }

    @Test
    fun `a flat or narrow range is widened around its middle`() {
        assertEquals(ValueRange(85.0, 95.0), plotRange(ValueRange(90.0, 90.0), minSpan = 10.0))
        assertEquals(ValueRange(6.0, 14.0), plotRange(ValueRange(9.0, 11.0), minSpan = 8.0))
    }
}
