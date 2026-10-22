package nl.stijnvdpol.klinedash.service

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.geo.LapGates
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.obd.Signal
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.core.trip.CsvTripWriter
import nl.stijnvdpol.klinedash.core.trip.DEFAULT_TRIP_FRESHNESS_MS
import nl.stijnvdpol.klinedash.core.trip.TripSummary
import nl.stijnvdpol.klinedash.core.trip.tripSampleOf
import nl.stijnvdpol.klinedash.data.TripSummaryCache

/** Signals that always get a column, because the trip summary is built from them. */
internal val SUMMARY_SIGNAL_IDS = listOf("rpm", "speed", "coolant")

private const val COLUMNS_SETTLE_MS = 20_000L
private const val FLUSH_EVERY_SAMPLES = 10
private const val BYTE_ORDER_MARK = "\uFEFF"

/**
 * One trip file being written: CSV (with GPS columns) through a [CsvTripWriter] on a buffered file
 * stream, plus the live statistics ([LiveTripAnalysis]). The statistics see every sample at once,
 * also those still waiting for the header. Not thread-safe; [TripRecorder] calls it under its lock.
 *
 * The CSV header has to be fixed before the first row, but the signals a trip contains are only
 * known once the poller has answered for each of them (slow signals take about 15 s). So the first
 * [settleMs] of samples are kept in memory, then the header is written with every signal that had a
 * fresh reading plus [SUMMARY_SIGNAL_IDS], and the kept samples follow. Signals that first show up
 * after that are not added to this trip. Until then the file is still empty;
 * recovery handles that.
 *
 * The file is UTF-8 and starts with a byte order mark: the header contains the degree sign (for
 * example "coolant (degree C)") and Excel on Windows misreads UTF-8 without it. [freshnessMs] stays
 * at the 30 s default on purpose: one row mixes FAST signals (about 1 s old) with SLOW ones (battery,
 * fuel trims, refreshed about every 15 s), and a shorter window would blank the slow columns half of
 * the time.
 *
 * Every [flushEverySamples] rows the data is flushed and synced to storage, so a power cut (ACC
 * off) loses at most that many seconds.
 */
internal class TripSession(
    val file: File,
    private val startMs: Long,
    private val catalog: List<Signal> = Signals.all,
    private val settleMs: Long = COLUMNS_SETTLE_MS,
    private val flushEverySamples: Int = FLUSH_EVERY_SAMPLES,
    private val freshnessMs: Long = DEFAULT_TRIP_FRESHNESS_MS,
) : Closeable {

    private class Sample(val nowMs: Long, val readings: Map<String, Reading>, val gps: GeoFix?)

    private val stream = FileOutputStream(file)
    private val writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8))
    private val csv = CsvTripWriter(writer, freshnessMs, includeGps = true)
    private val live = LiveTripAnalysis()
    private val preRoll = ArrayList<Sample>()
    private var isHeaderWritten = false
    private var samplesSinceFlush = 0

    init {
        writer.write(BYTE_ORDER_MARK)
    }

    /** Samples taken so far, including those still waiting for the header. */
    var sampleCount = 0
        private set

    /** The track the laps are timed for from now on, see [LiveTripAnalysis.setTrack]. */
    fun setTrack(trackId: Long?, gates: LapGates?) = live.setTrack(trackId, gates)

    /** See [LiveTripAnalysis.startNewGpsSegment]. */
    fun startNewGpsSegment() = live.startNewGpsSegment()

    val trackId: Long? get() = live.trackId
    val gates: LapGates? get() = live.gates

    /**
     * Adds one row; [gps] is the fix to record with it (the recorder passes only fresh ones).
     * @throws IOException when the file cannot be written (storage full or removed).
     */
    fun add(nowMs: Long, readings: Map<String, Reading>, gps: GeoFix? = null) {
        sampleCount++
        live.add(tripSampleOf(nowMs, readings, gps, freshnessMs))
        if (isHeaderWritten) {
            write(Sample(nowMs, readings, gps))
            return
        }
        preRoll += Sample(nowMs, readings, gps)
        if (nowMs - startMs >= settleMs) writeHeaderAndPreRoll()
    }

    /** What the screens show now. */
    fun liveState(): LiveTripState = live.state()

    /** Null while no sample was added. Complete after [close]. */
    fun summary(): TripSummary? = live.summary()

    /** The statistics for the trip row. */
    fun cache(): TripSummaryCache = live.cache()

    /** Writes whatever is still waiting for the header, then closes the file. */
    override fun close() {
        try {
            if (!isHeaderWritten && preRoll.isNotEmpty()) writeHeaderAndPreRoll()
            flush()
        } finally {
            writer.close()
        }
    }

    private fun writeHeaderAndPreRoll() {
        val seenIds = HashSet(SUMMARY_SIGNAL_IDS)
        for (sample in preRoll) {
            for ((id, reading) in sample.readings) {
                if (sample.nowMs - reading.timestampMs <= freshnessMs) seenIds += id
            }
        }
        csv.writeHeader(catalog.filter { it.id in seenIds })
        isHeaderWritten = true
        preRoll.forEach(::write)
        preRoll.clear()
        flush()
    }

    private fun write(sample: Sample) {
        csv.writeSample(sample.nowMs, sample.readings, sample.gps)
        if (++samplesSinceFlush >= flushEverySamples) flush()
    }

    private fun flush() {
        samplesSinceFlush = 0
        writer.flush()
        stream.fd.sync()
    }
}
