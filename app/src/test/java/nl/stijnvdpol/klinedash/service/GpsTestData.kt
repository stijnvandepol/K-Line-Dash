package nl.stijnvdpol.klinedash.service

import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.trip.CsvTripWriter
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.data.GateSpec
import nl.stijnvdpol.klinedash.data.Track
import nl.stijnvdpol.klinedash.data.newTrack

/** 2026-10-22T14:03:07Z, the instant the other trip tests use. */
const val TEST_BASE_MS = 1_792_677_787_000L

/** The default simulated circuit and a track whose start/finish line is the circuit's start line. */
val testCircuit = SimulatedCircuit()

fun circuitStartLine(): GateSpec = testCircuit.pointAt(0.0).let { (point, heading) -> GateSpec(point.lat, point.lon, heading) }

fun circuitTrack(id: Long = 1, sectors: List<GateSpec> = emptyList()): Track =
    newTrack("Test circuit", circuitStartLine(), sectors, createdMs = TEST_BASE_MS).copy(id = id)

/**
 * Fixes of a car driving the circuit at [speedKmh], one every [intervalMs], from [startOffsetM]
 * (negative = before the line) for [durationMs].
 */
fun circuitFixes(
    speedKmh: Double,
    durationMs: Long,
    intervalMs: Long = 200,
    startOffsetM: Double = -40.0,
    startMs: Long = TEST_BASE_MS,
): List<GeoFix> {
    val speedMps = speedKmh / 3.6
    return (0..durationMs / intervalMs).map { step ->
        val elapsedMs = step * intervalMs
        val (point, heading) = testCircuit.pointAt(startOffsetM + speedMps * elapsedMs / 1000.0)
        GeoFix(startMs + elapsedMs, point.lat, point.lon, speedMps, heading, accuracyM = 3.0, satellites = 12)
    }
}

/** A complete trip CSV (with GPS columns) of a drive over [fixes], one row per fix, speed 72 km/h and rpm 3000. */
fun tripCsvOf(fixes: List<GeoFix>, withGps: Boolean = true): String {
    val out = StringBuilder()
    val writer = CsvTripWriter(out, includeGps = true)
    writer.writeHeader(listOf("rpm", "speed", "coolant").map { checkNotNull(Signals.byId(it)) })
    for (fix in fixes) {
        val readings = mapOf(
            "rpm" to Reading("rpm", 3000.0, fix.timeMs),
            "speed" to Reading("speed", 72.0, fix.timeMs),
            "coolant" to Reading("coolant", 90.0, fix.timeMs),
        )
        writer.writeSample(fix.timeMs, readings, fix.takeIf { withGps })
    }
    return out.toString()
}
