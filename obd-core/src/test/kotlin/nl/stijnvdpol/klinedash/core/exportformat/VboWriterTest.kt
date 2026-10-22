package nl.stijnvdpol.klinedash.core.exportformat

import nl.stijnvdpol.klinedash.core.geo.GeoFix
import nl.stijnvdpol.klinedash.core.trip.TripColumn
import nl.stijnvdpol.klinedash.core.trip.TripSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VboWriterTest {

    private fun vbo(input: TripExportInput) = written { VboWriter.write(input, it) }

    private fun input(vararg samples: TripSample, columns: List<TripColumn> = emptyList()) =
        TripExportInput("t", ExportFixture.CREATED_MS, columns, samples.asSequence())

    @Test
    fun fixtureMatchesTheGoldenFile() {
        assertEquals(ExportFixture.golden("trip.vbo"), vbo(ExportFixture.input()).replace("\r\n", "\n"))
    }

    @Test
    fun everyLineEndsWithCarriageReturnAndLineFeed() {
        val text = vbo(ExportFixture.input())
        assertTrue(text.endsWith("\r\n"))
        assertFalse(text.replace("\r\n", "").contains('\n'))
    }

    @Test
    fun sectionsAppearInTheSpecifiedOrderSeparatedByBlankLines() {
        val lines = vbo(ExportFixture.input()).lines()
        val sections = lines.filter { it.startsWith("[") }
        assertEquals(listOf("[header]", "[channel units]", "[comments]", "[column names]", "[data]"), sections)
        for (section in sections) assertEquals("", lines[lines.indexOf(section) - 1].trimEnd('\r'))
        assertTrue(lines.first().startsWith("File created on "))
    }

    @Test
    fun columnNamesMatchTheHeaderEntriesAndEveryRowHasThatManyFields() {
        val lines = vbo(ExportFixture.input()).lines().map { it.trimEnd('\r') }
        val header = lines.subList(lines.indexOf("[header]") + 1, lines.indexOf("[channel units]") - 1)
        val columnNames = lines[lines.indexOf("[column names]") + 1].split(" ")
        val units = lines.subList(lines.indexOf("[channel units]") + 1, lines.indexOf("[comments]") - 1)
        assertEquals(columnNames.size, header.size)
        assertEquals(3, units.size)
        val rows = lines.drop(lines.indexOf("[data]") + 1).filter { it.isNotEmpty() }
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.split(" ").size == columnNames.size })
    }

    @Test
    fun latitudeIsMinutesNorthAndLongitudeIsMinutesWest() {
        val east = GeoFix(ExportFixture.T0, 51.985, 4.5)
        val southWest = GeoFix(ExportFixture.T0 + 1_000, -33.5, -70.25)
        val row = vbo(input(TripSample(ExportFixture.T0, emptyMap(), east), TripSample(ExportFixture.T0 + 1_000, emptyMap(), southWest)))
            .lines().map { it.trimEnd('\r') }.filter { it.startsWith("000 14") }

        assertEquals("000 140307.000 +03119.10000 -00270.00000 000.000 000.00 +00000.00", row[0])
        assertEquals("000 140308.000 -02010.00000 +04215.00000 000.000 000.00 +00000.00", row[1])
    }

    @Test
    fun missingObdValuesRepeatTheLastKnownOneAndStartAtZero() {
        val columns = listOf(TripColumn("rpm", "rpm"))
        val rows = vbo(
            input(
                TripSample(ExportFixture.T0, emptyMap(), GeoFix(ExportFixture.T0, 52.0, 5.0)),
                TripSample(ExportFixture.T0 + 1_000, mapOf("rpm" to 3_000.0), GeoFix(ExportFixture.T0 + 1_000, 52.0, 5.0)),
                TripSample(ExportFixture.T0 + 2_000, emptyMap(), GeoFix(ExportFixture.T0 + 2_000, 52.0, 5.0)),
                columns = columns,
            ),
        ).lines().map { it.trimEnd('\r') }.filter { it.startsWith("000 14") }

        assertEquals(listOf("0", "3000", "3000"), rows.map { it.split(" ").last() })
    }

    @Test
    fun channelNamesAndUnitsAreMadeSafeForTheFormat() {
        val columns = listOf(TripColumn("o2 b1/s1", "V"), TripColumn("temp", "°C"), TripColumn("mil_on", ""), TripColumn("rate", "l / h"))
        val text = vbo(input(columns = columns))
        val lines = text.lines().map { it.trimEnd('\r') }

        assertEquals("sats time lat long velocity heading height o2_b1_s1 temp mil_on rate", lines[lines.indexOf("[column names]") + 1])
        assertEquals(listOf("V", "degC", "-", "l_/_h"), lines.subList(lines.indexOf("[channel units]") + 1, lines.indexOf("[comments]") - 1))
    }

    @Test
    fun rowsWithoutAFixAndRepeatedFixesAreSkipped() {
        val fix = GeoFix(ExportFixture.T0, 52.0, 5.0)
        val rows = vbo(
            input(
                TripSample(ExportFixture.T0, emptyMap(), null),
                TripSample(ExportFixture.T0 + 1_000, emptyMap(), fix),
                TripSample(ExportFixture.T0 + 2_000, emptyMap(), fix),
            ),
        ).lines().filter { it.startsWith("000 14") }
        assertEquals(1, rows.size)
    }

    @Test
    fun tripWithoutObdChannelsOmitsTheChannelUnitsSection() {
        assertFalse(vbo(input()).contains("[channel units]"))
    }

    @Test
    fun titleWithLineBreaksStaysOnOneCommentLine() {
        val text = vbo(TripExportInput("a\r\nb", ExportFixture.CREATED_MS, emptyList(), emptySequence()))
        assertTrue(text.contains("K-Line Dash: a b\r\n"))
    }
}
