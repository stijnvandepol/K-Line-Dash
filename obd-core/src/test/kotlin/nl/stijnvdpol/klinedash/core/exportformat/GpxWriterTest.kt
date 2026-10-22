package nl.stijnvdpol.klinedash.core.exportformat

import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.trip.TripColumn
import nl.stijnvdpol.klinedash.core.trip.TripSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document

class GpxWriterTest {

    private fun gpx(input: TripExportInput) = written { GpxWriter.write(input, it) }

    /** Parses with a namespace-aware, XXE-hardened parser; throws when the text is not well-formed. */
    private fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    private fun count(document: Document, xpath: String): Int =
        (XPathFactory.newInstance().newXPath().evaluate("count($xpath)", document, XPathConstants.NUMBER) as Double).toInt()

    @Test
    fun fixtureMatchesTheGoldenFile() {
        assertEquals(ExportFixture.golden("trip.gpx"), gpx(ExportFixture.input()))
    }

    @Test
    fun fixtureIsWellFormedGpxWithOneSegmentPerLap() {
        val document = parse(gpx(ExportFixture.input()))
        val root = document.documentElement

        assertEquals("gpx", root.localName)
        assertEquals("1.1", root.getAttribute("version"))
        assertEquals("http://www.topografix.com/GPX/1/1", root.namespaceURI)
        assertEquals(1, count(document, "//*[local-name()='trk']"))
        assertEquals(2, count(document, "//*[local-name()='trkseg']"))
        assertEquals(4, count(document, "//*[local-name()='trkpt']"))
        assertEquals(2, count(document, "//*[local-name()='wpt']"))
    }

    @Test
    fun withoutLapsThereIsOneSegmentAndNoLapElements() {
        val xml = gpx(ExportFixture.input(withLaps = false))
        val document = parse(xml)
        assertEquals(1, count(document, "//*[local-name()='trkseg']"))
        assertEquals(4, count(document, "//*[local-name()='trkpt']"))
        assertFalse(xml.contains("kld:lap"))
        assertFalse(xml.contains("<wpt"))
    }

    @Test
    fun pointChildrenFollowTheGpxSchemaOrder() {
        val xml = gpx(ExportFixture.input())
        val point = xml.substringAfter("<trkpt").substringBefore("</trkpt>")
        assertTrue(point.indexOf("<time>") < point.indexOf("<sat>"))
        assertTrue(point.indexOf("<sat>") < point.indexOf("<extensions>"))
    }

    @Test
    fun tripWithoutAnyFixIsStillWellFormed() {
        val input = TripExportInput("empty", ExportFixture.CREATED_MS, emptyList(), emptySequence())
        val document = parse(gpx(input))
        assertEquals(0, count(document, "//*[local-name()='trkpt']"))
    }

    @Test
    fun invalidSpeedAndCourseAreLeftOut() {
        val fix = GeoFix(ExportFixture.T0, 52.0, 5.0, speedMps = -1.0, bearingDeg = Double.NaN)
        val xml = gpx(TripExportInput("t", ExportFixture.CREATED_MS, emptyList(), sequenceOf(TripSample(ExportFixture.T0, emptyMap(), fix))))
        assertFalse(xml.contains("gpxtpx:TrackPointExtension>"))
        assertFalse(xml.contains("<extensions>"))
        parse(xml)
    }

    @Test
    fun courseIsNormalisedIntoZeroToThreeSixty() {
        val fix = GeoFix(ExportFixture.T0, 52.0, 5.0, speedMps = 0.0, bearingDeg = -90.0)
        val xml = gpx(TripExportInput("t", ExportFixture.CREATED_MS, emptyList(), sequenceOf(TripSample(ExportFixture.T0, emptyMap(), fix))))
        assertTrue(xml.contains("<gpxtpx:speed>0.0</gpxtpx:speed><gpxtpx:course>270.0</gpxtpx:course>"))
    }

    @Test
    fun signalIdsBecomeValidElementNamesAndTitlesAreEscaped() {
        val columns = listOf(TripColumn("o2 b1/s1", "V"), TripColumn("1st", ""))
        val sample = TripSample(ExportFixture.T0, mapOf("o2 b1/s1" to 0.5, "1st" to 3.0), GeoFix(ExportFixture.T0, 52.0, 5.0))
        val xml = gpx(TripExportInput("A \"quoted\" <name> & \u0001more", ExportFixture.CREATED_MS, columns, sequenceOf(sample)))

        val document = parse(xml)
        assertTrue(xml.contains("<kld:o2_b1_s1>0.50</kld:o2_b1_s1>"))
        assertTrue(xml.contains("<kld:_1st>3.00</kld:_1st>"))
        assertEquals("A \"quoted\" <name> &  more", document.getElementsByTagName("name").item(0).textContent)
    }
}
