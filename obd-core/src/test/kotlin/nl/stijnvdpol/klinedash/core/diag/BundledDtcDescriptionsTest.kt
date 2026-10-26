package nl.stijnvdpol.klinedash.core.diag

import nl.stijnvdpol.klinedash.core.car.CarMake
import nl.stijnvdpol.klinedash.core.car.EngineNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BundledDtcDescriptionsTest {

    private val generic = BundledDtcDescriptions.load(null, null)
    private val toyota = BundledDtcDescriptions.load(CarMake.TOYOTA_LEXUS, null)
    private val toyota2zz = BundledDtcDescriptions.load(CarMake.TOYOTA_LEXUS, EngineNotes.TOYOTA_2ZZ_GE)
    private val vag = BundledDtcDescriptions.load(CarMake.VW_GROUP, null)
    private val honda = BundledDtcDescriptions.load(CarMake.HONDA_ACURA, null)
    private val nissan = BundledDtcDescriptions.load(CarMake.NISSAN_INFINITI, null)
    private val mazda = BundledDtcDescriptions.load(CarMake.MAZDA, null)
    private val ford = BundledDtcDescriptions.load(CarMake.FORD, null)

    private fun lines(fileName: String): List<String> = BundledDtcDescriptions.readResource(fileName)

    private fun parseGenericFile(): Map<String, String> = BundledDtcDescriptions.parseGeneric(lines("generic.tsv"))

    private fun parseTable(fileName: String): Map<String, BundledDtcDescriptions.Entry> =
        BundledDtcDescriptions.parseEntries(lines(fileName), fileName)

    private fun assertRejected(expectedFragment: String, block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException containing \"$expectedFragment\"")
        } catch (e: IllegalArgumentException) {
            assertTrue("message was: ${e.message}", e.message!!.contains(expectedFragment))
        }
    }

    private fun table(source: String, vararg rows: String) = BundledDtcDescriptions.Table(source, rows.toList())

    private fun synthetic(
        genericRows: List<String>,
        make: CarMake? = CarMake.TOYOTA_LEXUS,
        makeRows: List<String> = emptyList(),
        engineRows: List<String> = emptyList(),
    ): BundledDtcDescriptions = BundledDtcDescriptions.fromTables(
        BundledDtcDescriptions.parseGeneric(genericRows),
        make,
        BundledDtcDescriptions.Table("make.tsv", makeRows),
        BundledDtcDescriptions.Table("engine.tsv", engineRows),
    )

    // ---- the bundled files ----

    @Test
    fun `the generic file has only valid unique non-manufacturer codes with short descriptions`() {
        val parsed = parseGenericFile()
        assertTrue("generic.tsv is suspiciously small: ${parsed.size}", parsed.size > 500)
        for ((code, description) in parsed) {
            assertFalse(code, Dtc(code).isManufacturerSpecific)
            assertTrue("$code description", description.isNotBlank() && description.length < 90)
        }
    }

    @Test
    fun `every make table has valid unique codes of the right kind`() {
        val genericCodes = parseGenericFile().keys
        for (fileName in MAKE_TABLES.keys) {
            val parsed = parseTable(fileName)
            assertTrue("$fileName is suspiciously small: ${parsed.size}", parsed.size >= MAKE_TABLES.getValue(fileName))
            for ((code, entry) in parsed) {
                if (Dtc(code).isManufacturerSpecific) {
                    assertTrue("$fileName $code needs a description", entry.description.isNotEmpty())
                } else {
                    assertTrue("$fileName $code is a generic code: notes only", entry.description.isEmpty())
                    assertTrue("$fileName $code is not in generic.tsv", code in genericCodes)
                }
                assertTrue("$fileName $code description", entry.description.length < MAX_TEXT_LENGTH)
                assertTrue("$fileName $code note", entry.note.length < MAX_NOTE_LENGTH)
            }
        }
    }

    @Test
    fun `the engine table only adds notes to codes that already have a meaning`() {
        val genericCodes = parseGenericFile().keys
        val makeEntries = parseTable("make-toyota.tsv")
        val engine = parseTable("engine-2zz-ge.tsv")
        assertTrue(engine.size > 10)
        for ((code, entry) in engine) {
            assertTrue("$code: the engine table holds notes only", entry.description.isEmpty())
            assertTrue(code, entry.note.isNotEmpty())
            assertTrue("$code has no meaning below the engine layer", code in genericCodes || code in makeEntries)
        }
    }

    @Test
    fun `every registered table code resolves to a real description for its make`() {
        for ((make, fileName) in listOf(
            CarMake.TOYOTA_LEXUS to "make-toyota.tsv",
            CarMake.VW_GROUP to "make-vag.tsv",
            CarMake.HONDA_ACURA to "make-honda.tsv",
            CarMake.NISSAN_INFINITI to "make-nissan.tsv",
            CarMake.MAZDA to "make-mazda.tsv",
            CarMake.FORD to "make-ford.tsv",
        )) {
            val db = BundledDtcDescriptions.load(make, null)
            for (code in parseTable(fileName).keys) {
                val description = db.describe(Dtc(code))
                assertNotNull("$make $code", description)
                assertNotEquals("$make $code", manufacturerSpecificDescription(make), description!!.description)
            }
        }
    }

    @Test
    fun `the VAG and Honda tables are a selection of the intended size`() {
        assertTrue(parseTable("make-vag.tsv").size in 80..150)
        assertTrue(parseTable("make-honda.tsv").size in 30..60)
        assertTrue(parseTable("make-nissan.tsv").size in 30..90)
        assertTrue(parseTable("make-mazda.tsv").size in 15..60)
        assertTrue(parseTable("make-ford.tsv").size in 30..90)
    }

    @Test
    fun `the Toyota table carries no engine-specific remarks`() {
        for ((code, entry) in parseTable("make-toyota.tsv")) {
            assertFalse("$code ${entry.note}", entry.note.contains("2ZZ") || entry.note.contains("flash code"))
        }
    }

    // ---- spot checks of well-known codes ----

    @Test
    fun `well-known generic codes have the standard wording for every make`() {
        for (db in listOf(generic, toyota, toyota2zz, vag, honda, nissan, mazda, ford)) {
            assertEquals("Catalyst System Efficiency Below Threshold (Bank 1)", db.describe(Dtc("P0420"))!!.description)
            assertEquals("Lost Communication With ECM/PCM \"A\"", db.describe(Dtc("U0100"))!!.description)
            assertEquals("Cylinder 4 Misfire Detected", db.describe(Dtc("P0304"))!!.description)
        }
    }

    @Test
    fun `unknown standard code is null`() {
        assertNull(generic.describe(Dtc("P0999")))
        assertNull(toyota2zz.describe(Dtc("P0999")))
        assertNull(vag.describe(Dtc("U0999")))
    }

    @Test
    fun `a generic code without a note has a null note`() {
        assertEquals(DtcDescription("Fuel Trim Malfunction (Bank 1)", null), toyota.describe(Dtc("P0170")))
    }

    @Test
    fun `P1349 means a different thing per make`() {
        val toyotaMeaning = toyota.describe(Dtc("P1349"))!!
        assertEquals("VVT System Malfunction (Bank 1)", toyotaMeaning.description)
        assertEquals(toyotaMeaning.description, toyota2zz.describe(Dtc("P1349"))!!.description)
        assertEquals("Ignition coil output stage 2: open circuit", vag.describe(Dtc("P1349"))!!.description)
        assertEquals(manufacturerSpecificDescription(CarMake.HONDA_ACURA), honda.describe(Dtc("P1349"))!!.description)
        assertEquals(manufacturerSpecificDescription(CarMake.NISSAN_INFINITI), nissan.describe(Dtc("P1349"))!!.description)
        assertEquals(manufacturerSpecificDescription(CarMake.MAZDA), mazda.describe(Dtc("P1349"))!!.description)
        assertEquals(manufacturerSpecificDescription(CarMake.FORD), ford.describe(Dtc("P1349"))!!.description)
        assertEquals(MANUFACTURER_SPECIFIC_DESCRIPTION, generic.describe(Dtc("P1349"))!!.description)
        val bmw = BundledDtcDescriptions.load(CarMake.BMW_MINI, null)
        assertEquals(manufacturerSpecificDescription(CarMake.BMW_MINI), bmw.describe(Dtc("P1349"))!!.description)
    }

    @Test
    fun `Honda and VAG tables give their own meanings`() {
        assertEquals("VTEC system malfunction", honda.describe(Dtc("P1259"))!!.description)
        assertEquals("EGR valve lift insufficient", honda.describe(Dtc("P1491"))!!.description)
        assertEquals("Long-term fuel trim (under load), bank 1: too lean", vag.describe(Dtc("P1128"))!!.description)
        assertEquals("Cylinder 1 injector (N30): short to positive", vag.describe(Dtc("P1213"))!!.description)
        assertNotNull(vag.describe(Dtc("P1128"))!!.vehicleNote)
        assertNull(honda.describe(Dtc("P1259"))!!.vehicleNote)
    }

    @Test
    fun `Nissan Mazda and Ford tables give their own meanings`() {
        assertEquals("Engine overheating (over temperature)", nissan.describe(Dtc("P1217"))!!.description)
        assertEquals("Closed-loop fuel control, bank 1: function fault", nissan.describe(Dtc("P1148"))!!.description)
        assertNotNull(nissan.describe(Dtc("P1217"))!!.vehicleNote)
        assertNull(nissan.describe(Dtc("P1148"))!!.vehicleNote)
        assertEquals("Generator terminal B: open circuit", mazda.describe(Dtc("P1634"))!!.description)
        assertEquals("Ignition coil A: primary circuit fault", ford.describe(Dtc("P1352"))!!.description)
    }

    @Test
    fun `P1260 differs per make`() {
        assertEquals("Theft detected: engine disabled by the anti-theft system", ford.describe(Dtc("P1260"))!!.description)
        assertEquals("Theft detected: engine disabled by the immobilizer", mazda.describe(Dtc("P1260"))!!.description)
        assertEquals("Pump injector valve, cylinder 1 (N240): implausible signal", vag.describe(Dtc("P1260"))!!.description)
        assertEquals(manufacturerSpecificDescription(CarMake.NISSAN_INFINITI), nissan.describe(Dtc("P1260"))!!.description)
        assertEquals(manufacturerSpecificDescription(CarMake.HONDA_ACURA), honda.describe(Dtc("P1260"))!!.description)
    }

    // ---- layers: make and engine notes ----

    @Test
    fun `Toyota without engine notes gets Toyota meanings and no 2ZZ remarks`() {
        val vvt = toyota.describe(Dtc("P1349"))!!
        assertTrue(vvt.vehicleNote!!.contains("oil level"))
        assertFalse(vvt.vehicleNote!!.contains("flash code"))
        val lean = toyota.describe(Dtc("P0171"))!!
        assertEquals("System Too Lean (Bank 1)", lean.description)
        assertNull(lean.vehicleNote)
        assertNull(toyota.describe(Dtc("P0300"))!!.vehicleNote)
    }

    @Test
    fun `the 2ZZ-GE notes are added on top for a Toyota`() {
        val lean = toyota2zz.describe(Dtc("P0171"))!!
        assertEquals("System Too Lean (Bank 1)", lean.description)
        assertTrue(lean.vehicleNote!!.contains("vacuum leak"))
        assertNotNull(toyota2zz.describe(Dtc("P0300"))!!.vehicleNote)

        val vvt = toyota2zz.describe(Dtc("P1349"))!!
        assertEquals("VVT System Malfunction (Bank 1)", vvt.description)
        assertTrue(vvt.vehicleNote!!.contains("flash code 59"))
    }

    @Test
    fun `engine notes of another make are ignored`() {
        assertSame(honda, BundledDtcDescriptions.load(CarMake.HONDA_ACURA, EngineNotes.TOYOTA_2ZZ_GE))
        assertSame(generic, BundledDtcDescriptions.load(null, EngineNotes.TOYOTA_2ZZ_GE))
        assertNull(honda.describe(Dtc("P0171"))!!.vehicleNote)
    }

    // ---- fallback for manufacturer-specific codes ----

    @Test
    fun `no make gives the generic fallback and non-manufacturer unknowns stay null`() {
        assertEquals(DtcDescription(MANUFACTURER_SPECIFIC_DESCRIPTION, null), generic.describe(Dtc("P1999")))
        assertEquals(DtcDescription(MANUFACTURER_SPECIFIC_DESCRIPTION, null), generic.describe(Dtc("U1000")))
        assertNull(generic.describe(Dtc("P3400")))
    }

    @Test
    fun `a make with a table also gets its make fallback for a code the table lacks`() {
        assertEquals(manufacturerSpecificDescription(CarMake.TOYOTA_LEXUS), toyota2zz.describe(Dtc("P1999"))!!.description)
        assertEquals(manufacturerSpecificDescription(CarMake.VW_GROUP), vag.describe(Dtc("P1999"))!!.description)
        assertEquals(manufacturerSpecificDescription(CarMake.HONDA_ACURA), honda.describe(Dtc("U2000"))!!.description)
    }

    @Test
    fun `every make has its own short fallback sentence`() {
        assertEquals(MANUFACTURER_SPECIFIC_DESCRIPTION, manufacturerSpecificDescription(null))
        val seen = HashSet<String>()
        for (make in CarMake.entries) {
            val text = manufacturerSpecificDescription(make)
            assertNotEquals(make.name, MANUFACTURER_SPECIFIC_DESCRIPTION, text)
            assertTrue("$make: ${text.length}", text.length < MAX_TEXT_LENGTH)
            assertTrue("$make: $text", text.contains("-specific code."))
            assertFalse("$make: no URLs", text.contains("http") || text.contains("www"))
            assertTrue("$make duplicates another make", seen.add(text))
            assertEquals(text, BundledDtcDescriptions.load(make, null).describe(Dtc("P1999"))!!.description)
        }
    }

    @Test
    fun `fallbacks name the tool that reads the full fault memory`() {
        val tools = mapOf(
            CarMake.BMW_MINI to "ISTA",
            CarMake.MERCEDES_SMART to "Xentry",
            CarMake.VW_GROUP to "ODIS",
            CarMake.RENAULT_DACIA to "CLIP",
            CarMake.PSA to "Diagbox",
            CarMake.FIAT_ALFA_LANCIA to "Multiecuscan",
            CarMake.VOLVO to "VIDA",
            CarMake.FORD to "FORScan",
            CarMake.TOYOTA_LEXUS to "Techstream",
            CarMake.HONDA_ACURA to "HDS",
            CarMake.NISSAN_INFINITI to "Consult",
        )
        for ((make, tool) in tools) assertTrue(make.name, manufacturerSpecificDescription(make).contains(tool))
        for (make in listOf(CarMake.MAZDA, CarMake.MITSUBISHI, CarMake.SUBARU, CarMake.SUZUKI, CarMake.HYUNDAI_KIA, CarMake.OPEL_VAUXHALL)) {
            assertTrue(make.name, manufacturerSpecificDescription(make).contains("dealer's diagnostic tool"))
        }
    }

    // ---- caching ----

    @Test
    fun `load returns the same instance for the same pair`() {
        assertSame(BundledDtcDescriptions.load(null, null), BundledDtcDescriptions.load(null, null))
        assertSame(
            BundledDtcDescriptions.load(CarMake.TOYOTA_LEXUS, EngineNotes.TOYOTA_2ZZ_GE),
            BundledDtcDescriptions.load(CarMake.TOYOTA_LEXUS, EngineNotes.TOYOTA_2ZZ_GE),
        )
        for (make in CarMake.entries) assertSame(make.name, BundledDtcDescriptions.load(make, null), BundledDtcDescriptions.load(make, null))
    }

    @Test
    fun `different pairs are different instances`() {
        assertNotSame(toyota, toyota2zz)
        assertNotSame(toyota, vag)
        assertNotSame(generic, toyota)
    }

    @Test
    fun `concurrent loads of one pair share one instance`() {
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val results = pool.invokeAll(List(THREADS) { Callable { BundledDtcDescriptions.load(CarMake.MAZDA, null) } })
                .map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            for (result in results) assertSame(results.first(), result)
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- manufacturer-specific ranges (SAE J2012) ----

    @Test
    fun `manufacturer-specific ranges are recognised`() {
        val manufacturer = listOf("P1000", "P1FFF", "P3000", "P33FF", "B1000", "B2FFF", "C1234", "C2000", "U1000", "U2FFF")
        for (code in manufacturer) assertTrue(code, Dtc(code).isManufacturerSpecific)
    }

    @Test
    fun `generic and reserved ranges are not manufacturer-specific`() {
        val others = listOf("P0000", "P0FFF", "P2000", "P2FFF", "P3400", "P3FFF", "B0000", "B3000", "C0035", "C3000", "U0100", "U3000")
        for (code in others) assertFalse(code, Dtc(code).isManufacturerSpecific)
    }

    // ---- merge rules ----

    @Test
    fun `engine description beats make description beats generic`() {
        val db = synthetic(
            genericRows = listOf("P0171\tGeneric lean", "P0300\tGeneric misfire"),
            makeRows = listOf("P0171\tMake lean\tmake note", "P1349\tMake VVT\t"),
            engineRows = listOf("P0171\tEngine lean\t", "P1349\t\tengine note"),
        )
        assertEquals(DtcDescription("Engine lean", "make note"), db.describe(Dtc("P0171")))
        assertEquals(DtcDescription("Make VVT", "engine note"), db.describe(Dtc("P1349")))
        assertEquals(DtcDescription("Generic misfire", null), db.describe(Dtc("P0300")))
    }

    @Test
    fun `engine note replaces the make note and an empty one keeps it`() {
        val db = synthetic(
            genericRows = listOf("P0171\tLean", "P0172\tRich"),
            makeRows = listOf("P0171\t\tmake note", "P0172\t\tmake note"),
            engineRows = listOf("P0171\t\tengine note"),
        )
        assertEquals("engine note", db.describe(Dtc("P0171"))!!.vehicleNote)
        assertEquals("make note", db.describe(Dtc("P0172"))!!.vehicleNote)
    }

    @Test
    fun `empty make description falls back to generic and keeps the note`() {
        val db = synthetic(listOf("P0171\tLean"), makeRows = listOf("P0171\t\tcheck hoses"))
        assertEquals(DtcDescription("Lean", "check hoses"), db.describe(Dtc("P0171")))
    }

    @Test
    fun `a description without a note has a null note`() {
        val db = synthetic(emptyList(), makeRows = listOf("P1349\tVVT System Malfunction\t"))
        assertEquals(DtcDescription("VVT System Malfunction", null), db.describe(Dtc("P1349")))
    }

    @Test
    fun `a manufacturer code in no layer gets the make fallback`() {
        val db = synthetic(emptyList(), make = CarMake.BMW_MINI)
        assertEquals(manufacturerSpecificDescription(CarMake.BMW_MINI), db.describe(Dtc("P1500"))!!.description)
        assertNull(db.describe(Dtc("P0999")))
    }

    @Test
    fun `a make note without any description is rejected at construction`() {
        assertRejected("P0171") { synthetic(emptyList(), makeRows = listOf("P0171\t\tcheck hoses")) }
    }

    @Test
    fun `an engine note needs a description in the make table or the generic file`() {
        assertRejected("P1349") { synthetic(emptyList(), engineRows = listOf("P1349\t\tcheck oil")) }
        synthetic(emptyList(), makeRows = listOf("P1349\tVVT\t"), engineRows = listOf("P1349\t\tcheck oil"))
        synthetic(listOf("P0171\tLean"), engineRows = listOf("P0171\t\tcheck hoses"))
    }

    // ---- parsing ----

    @Test
    fun `comments and blank lines are skipped`() {
        val parsed = BundledDtcDescriptions.parseGeneric(listOf("# header", "", "P0171\tLean", "   "))
        assertEquals(mapOf("P0171" to "Lean"), parsed)
    }

    @Test
    fun `malformed generic line fails with its line number`() {
        assertRejected("generic.tsv line 3") {
            BundledDtcDescriptions.parseGeneric(listOf("# header", "P0171\tLean", "P0172 no tab"))
        }
    }

    @Test
    fun `invalid code fails with its line number`() {
        assertRejected("generic.tsv line 2") {
            BundledDtcDescriptions.parseGeneric(listOf("P0171\tLean", "X1234\tNot a code"))
        }
    }

    @Test
    fun `empty generic description fails with its line number`() {
        assertRejected("generic.tsv line 1") {
            BundledDtcDescriptions.parseGeneric(listOf("P0171\t"))
        }
    }

    @Test
    fun `duplicate code fails with the line number of the second occurrence`() {
        assertRejected("generic.tsv line 2") {
            BundledDtcDescriptions.parseGeneric(listOf("P0171\tLean", "P0171\tLean again"))
        }
        assertRejected("make-vag.tsv line 2") {
            BundledDtcDescriptions.parseEntries(listOf("P1349\ta\tb", "P1349\tc\td"), "make-vag.tsv")
        }
    }

    @Test
    fun `entry line with the wrong column count fails with the file and line number`() {
        assertRejected("make-toyota.tsv line 2") {
            BundledDtcDescriptions.parseEntries(listOf("# header", "P1349\tonly two columns"), "make-toyota.tsv")
        }
    }

    @Test
    fun `entry line with neither description nor note fails with the file and line number`() {
        assertRejected("engine-2zz-ge.tsv line 1") {
            BundledDtcDescriptions.parseEntries(listOf("P1349\t\t"), "engine-2zz-ge.tsv")
        }
    }

    @Test
    fun `a malformed table is reported with the name of its file`() {
        assertRejected("make.tsv line 1") {
            BundledDtcDescriptions.fromTables(emptyMap(), null, table("make.tsv", "P1349 no tabs"), null)
        }
    }

    private companion object {
        /** Bundled make tables and the least number of codes each must have. */
        val MAKE_TABLES = mapOf(
            "make-toyota.tsv" to 10,
            "make-vag.tsv" to 80,
            "make-honda.tsv" to 30,
            "make-nissan.tsv" to 30,
            "make-mazda.tsv" to 15,
            "make-ford.tsv" to 30,
        )
        const val MAX_TEXT_LENGTH = 110
        const val MAX_NOTE_LENGTH = 220
        const val THREADS = 8
        const val TIMEOUT_SECONDS = 10L
    }
}
