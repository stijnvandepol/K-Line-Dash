package nl.stijnvdpol.klinedash.core.exportformat

import java.io.StringReader
import nl.stijnvdpol.klinedash.core.geo.Gate
import nl.stijnvdpol.klinedash.core.geo.Lap
import nl.stijnvdpol.klinedash.core.geo.LapGates
import nl.stijnvdpol.klinedash.core.geo.LatLon
import nl.stijnvdpol.klinedash.core.trip.LapStats
import nl.stijnvdpol.klinedash.core.trip.TripColumn
import nl.stijnvdpol.klinedash.core.trip.TripSample
import nl.stijnvdpol.klinedash.core.trip.readTripCsv

/** The small fixture trip shared by the golden-file tests: six rows at 1 Hz starting 14:03:07 UTC. */
object ExportFixture {
    /** 2026-10-22T14:03:07.000Z */
    const val T0 = 1_792_677_787_000L
    const val TITLE = "Test trip <&>"

    /** The export is made one minute after the trip started. */
    const val CREATED_MS = T0 + 60_000L

    private fun resourceText(name: String): String =
        checkNotNull(ExportFixture::class.java.getResourceAsStream("/exportformat/$name")) { "Missing fixture $name" }
            .use { String(it.readBytes(), Charsets.UTF_8) }

    /** Golden text with LF line ends, whatever the checkout converted them to. */
    fun golden(name: String): String = resourceText(name).replace("\r\n", "\n")

    val columns: List<TripColumn> = readTripCsv(StringReader(resourceText("trip_raw.csv"))).columns

    fun samples(): Sequence<TripSample> = readTripCsv(StringReader(resourceText("trip_raw.csv"))).samples

    val gates = LapGates(
        startFinish = Gate(LatLon(51.9849, -0.9750), LatLon(51.9851, -0.9750)),
        sectors = listOf(Gate(LatLon(51.9850, -0.9740), LatLon(51.9852, -0.9740))),
    )

    /** Lap 1 and 2 cover the fix rows; lap 3 is invalid and has no rows. */
    val laps = listOf(
        Lap(1, T0 + 500, T0 + 3_500, listOf(1_200L, 1_800L), 100.0, true),
        Lap(2, T0 + 3_500, T0 + 6_750, emptyList(), 100.0, true),
        Lap(3, T0 + 6_750, T0 + 10_250, listOf(1_000L, 2_500L), 100.0, false),
    )

    val lapStats = listOf(
        LapStats(laps[0], maxSpeedKmh = 72.0, minSpeedKmh = 36.0, maxRpm = 3_000.0, fullThrottleTimeMs = 1_500L),
        LapStats(laps[1], maxSpeedKmh = 70.0, minSpeedKmh = 40.0, maxRpm = 3_200.0, fullThrottleTimeMs = 0L),
        LapStats(laps[2], maxSpeedKmh = null, minSpeedKmh = null, maxRpm = null, fullThrottleTimeMs = 0L),
    )

    fun input(withLaps: Boolean = true) = TripExportInput(
        title = TITLE,
        createdMs = CREATED_MS,
        columns = columns,
        samples = samples(),
        laps = if (withLaps) laps else emptyList(),
        gates = if (withLaps) gates else null,
    )
}

/** Runs [write] against a fresh buffer and returns what it wrote. */
fun written(write: (Appendable) -> Unit): String = StringBuilder().also(write).toString()
