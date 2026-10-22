package nl.stijnvdpol.klinedash.core.exportformat

import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.geo.Geodesy
import nl.stijnvdpol.klinedash.core.geo.Gate
import nl.stijnvdpol.klinedash.core.geo.LapIndex
import nl.stijnvdpol.klinedash.core.trip.TripCsvFormat
import nl.stijnvdpol.klinedash.core.trip.TripSample

/**
 * GPX 1.1 writer. One `<trk>` per trip; with laps, one `<trkseg>` per lap (plus one for the drive
 * before the first and after the last lap), otherwise a single segment. Every track point has the
 * fix time (UTC, milliseconds) and, when known, `<sat>`. Extensions, in the order GPX requires them
 * last in the point:
 *
 * - Garmin TrackPointExtension v2 (`gpxtpx:`): `speed` in m/s and `course` in degrees, only when
 *   the value is a valid number (speed at least 0, course normalised to 0..360).
 * - Own namespace `kld:` (`urn:nl.stijnvdpol.klinedash:gpx:1`): `lap` (number), `accuracy` (m) and
 *   one element per OBD signal, named by the signal id, with the catalog's decimals. Generic GPX
 *   tools ignore these; the CSV remains the lossless companion.
 *
 * Start/finish and sector gates become `<wpt>`s at their midpoint. There is no altitude, so no
 * `<ele>`. Samples without a new fix are skipped.
 */
object GpxWriter {
    private const val NAMESPACE = "http://www.topografix.com/GPX/1/1"
    private const val TPX_NAMESPACE = "http://www.garmin.com/xmlschemas/TrackPointExtension/v2"
    private const val OWN_NAMESPACE = "urn:nl.stijnvdpol.klinedash:gpx:1"
    private const val POSITION_DECIMALS = 7
    private const val VALUE_DECIMALS = 1

    fun write(trip: TripExportInput, out: Appendable) {
        out.line("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        out.line(
            "<gpx version=\"1.1\" creator=\"K-Line Dash\" xmlns=\"$NAMESPACE\" " +
                "xmlns:gpxtpx=\"$TPX_NAMESPACE\" xmlns:kld=\"$OWN_NAMESPACE\">",
        )
        out.line("  <metadata><name>${trip.title.xmlText()}</name><time>${TripCsvFormat.formatTime(trip.createdMs)}</time></metadata>")
        writeWaypoints(trip, out)
        out.line("  <trk>")
        out.line("    <name>${trip.title.xmlText()}</name>")
        writeSegments(trip, out)
        out.line("  </trk>")
        out.line("</gpx>")
    }

    private fun writeWaypoints(trip: TripExportInput, out: Appendable) {
        val gates = trip.gates ?: return
        out.waypoint(gates.startFinish, "Start/Finish")
        gates.sectors.forEachIndexed { index, gate -> out.waypoint(gate, "Sector ${index + 1}") }
    }

    private fun Appendable.waypoint(gate: Gate, name: String) {
        val point = gate.midpoint
        line(
            "  <wpt lat=\"${TripCsvFormat.formatFixed(point.lat, POSITION_DECIMALS)}\" " +
                "lon=\"${TripCsvFormat.formatFixed(point.lon, POSITION_DECIMALS)}\"><name>$name</name></wpt>",
        )
    }

    private fun writeSegments(trip: TripExportInput, out: Appendable) {
        val lapIndex = LapIndex(trip.laps)
        var isSegmentOpen = false
        var segmentLap: Int? = null
        for (sample in trip.samples.withNewFix()) {
            val fix = sample.gps ?: continue
            val lap = lapIndex.numberAt(fix.timeMs)
            if (!isSegmentOpen || lap != segmentLap) {
                if (isSegmentOpen) out.line("    </trkseg>")
                out.line("    <trkseg>")
                isSegmentOpen = true
                segmentLap = lap
            }
            out.writePoint(trip, sample, fix, lap)
        }
        if (isSegmentOpen) out.line("    </trkseg>")
    }

    private fun Appendable.writePoint(trip: TripExportInput, sample: TripSample, fix: GeoFix, lap: Int?) {
        line(
            "      <trkpt lat=\"${TripCsvFormat.formatFixed(fix.lat, POSITION_DECIMALS)}\" " +
                "lon=\"${TripCsvFormat.formatFixed(fix.lon, POSITION_DECIMALS)}\">",
        )
        line("        <time>${TripCsvFormat.formatTime(fix.timeMs)}</time>")
        fix.satellites?.let { line("        <sat>$it</sat>") }
        val extensions = garminExtension(fix) + ownExtensions(trip, sample, fix, lap)
        if (extensions.isNotEmpty()) {
            line("        <extensions>")
            extensions.forEach { line("          $it") }
            line("        </extensions>")
        }
        line("      </trkpt>")
    }

    private fun garminExtension(fix: GeoFix): List<String> {
        val speed = fix.speedMps?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { "<gpxtpx:speed>${TripCsvFormat.formatFixed(it, VALUE_DECIMALS)}</gpxtpx:speed>" }
        val course = fix.bearingDeg?.takeIf { it.isFinite() }
            ?.let { "<gpxtpx:course>${TripCsvFormat.formatFixed(Geodesy.normalizeDegrees(it), VALUE_DECIMALS)}</gpxtpx:course>" }
        if (speed == null && course == null) return emptyList()
        return listOf("<gpxtpx:TrackPointExtension>${speed.orEmpty()}${course.orEmpty()}</gpxtpx:TrackPointExtension>")
    }

    private fun ownExtensions(trip: TripExportInput, sample: TripSample, fix: GeoFix, lap: Int?): List<String> {
        val elements = ArrayList<String>()
        lap?.let { elements += "<kld:lap>$it</kld:lap>" }
        fix.accuracyM?.takeIf { it.isFinite() }
            ?.let { elements += "<kld:accuracy>${TripCsvFormat.formatFixed(it, VALUE_DECIMALS)}</kld:accuracy>" }
        for (column in trip.columns) {
            val value = sample.values[column.id] ?: continue
            val name = column.id.xmlName()
            elements += "<kld:$name>${TripCsvFormat.formatFixed(value, column.decimals())}</kld:$name>"
        }
        return elements
    }

    private fun Appendable.line(text: String) {
        append(text).append('\n')
    }

    /** An XML element name: invalid characters become underscores, a leading digit gets an underscore. */
    private fun String.xmlName(): String {
        val cleaned = replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return if (cleaned.firstOrNull()?.let { it.isLetter() || it == '_' } == true) cleaned else "_$cleaned"
    }

    /** Escapes text content and drops characters XML 1.0 cannot carry. */
    private fun String.xmlText(): String = buildString {
        for (char in this@xmlText) {
            when {
                char == '&' -> append("&amp;")
                char == '<' -> append("&lt;")
                char == '>' -> append("&gt;")
                char == '"' -> append("&quot;")
                char.code < 0x20 && char != '\t' -> append(' ')
                else -> append(char)
            }
        }
    }
}
