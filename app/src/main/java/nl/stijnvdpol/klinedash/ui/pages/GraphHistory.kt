package nl.stijnvdpol.klinedash.ui.pages

import nl.stijnvdpol.klinedash.core.obd.Reading

/** How much history a graph cell shows. */
internal const val GRAPH_WINDOW_MS = 60_000L

/** 60 s at the 10 readings a second no ELM327 link reaches; the oldest point is overwritten first. */
internal const val HISTORY_CAPACITY = 600

/**
 * The last [capacity] readings of one signal in two primitive arrays, oldest first, so recording and
 * drawing allocate nothing per point. Times only go up: a reading that is not newer than the last
 * one (the same reading seen again) is ignored.
 */
internal class SignalHistory(private val capacity: Int = HISTORY_CAPACITY) {
    private val times = LongArray(capacity)
    private val values = DoubleArray(capacity)
    private var oldest = 0

    var size = 0
        private set

    /** True when the point was added. */
    fun append(timeMs: Long, value: Double): Boolean {
        if (size > 0 && timeMs <= timeAt(size - 1)) return false
        val slot = (oldest + size) % capacity
        times[slot] = timeMs
        values[slot] = value
        if (size < capacity) size++ else oldest = (oldest + 1) % capacity
        return true
    }

    /** Point [index], 0 being the oldest. */
    fun timeAt(index: Int): Long = times[(oldest + index) % capacity]

    fun valueAt(index: Int): Double = values[(oldest + index) % capacity]

    /** Index of the first point at or after [timeMs]; [size] when there is none. */
    fun firstIndexAtOrAfter(timeMs: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (timeAt(mid) < timeMs) low = mid + 1 else high = mid
        }
        return low
    }
}

/** Histories of the signals that have a graph on some page, filled from the poller's readings. */
internal class GraphHistory {
    private val bySignal = HashMap<String, SignalHistory>()

    fun of(signalId: String): SignalHistory? = bySignal[signalId]

    /** Adds every new reading of [signalIds]; true when at least one point was added. */
    fun record(signalIds: Set<String>, readings: Map<String, Reading>): Boolean {
        var added = false
        for (id in signalIds) {
            val reading = readings[id] ?: continue
            val history = bySignal.getOrPut(id) { SignalHistory() }
            if (history.append(reading.timestampMs, reading.value)) added = true
        }
        return added
    }
}

/** Lowest and highest value of the points at or after [sinceMs]; null when there are none. */
internal fun SignalHistory.rangeSince(sinceMs: Long): ValueRange? {
    val first = firstIndexAtOrAfter(sinceMs)
    if (first >= size) return null
    var low = valueAt(first)
    var high = low
    for (i in first + 1 until size) {
        val value = valueAt(i)
        if (value < low) low = value
        if (value > high) high = value
    }
    return ValueRange(low, high)
}

internal data class ValueRange(val low: Double, val high: Double)

/**
 * The span the plot shows: the data range, but at least [minSpan] wide and centred on the data, so
 * a flat line sits in the middle and noise on a steady signal does not fill the whole cell.
 */
internal fun plotRange(data: ValueRange, minSpan: Double): ValueRange {
    val span = data.high - data.low
    if (span >= minSpan) return data
    val middle = (data.high + data.low) / 2
    return ValueRange(middle - minSpan / 2, middle + minSpan / 2)
}
