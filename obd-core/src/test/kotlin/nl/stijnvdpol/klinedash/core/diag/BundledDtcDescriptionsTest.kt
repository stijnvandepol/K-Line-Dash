package nl.stijnvdpol.klinedash.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BundledDtcDescriptionsTest {

    private val toyota = BundledDtcDescriptions.load(includeToyota2zz = true)
    private val genericOnly = BundledDtcDescriptions.load(includeToyota2zz = false)

    private fun describe(code: String): DtcDescription? = toyota.describe(Dtc(code))

    private fun describeGeneric(code: String): DtcDescription? = genericOnly.describe(Dtc(code))

    private fun resourceLines(path: String): List<String> =
        BundledDtcDescriptions::class.java.getResourceAsStream(path)!!
            .bufferedReader(Charsets.UTF_8).use { it.readLines() }

    private fun assertRejected(expectedFragment: String, block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException containing \"$expectedFragment\"")
        } catch (e: IllegalArgumentException) {
            assertTrue("message was: ${e.message}", e.message!!.contains(expectedFragment))
        }
    }

    // ---- the real bundled files ----

    @Test
    fun `load returns the same instance every time`() {
        assertSame(BundledDtcDescriptions.load(true), BundledDtcDescriptions.load(true))
        assertSame(BundledDtcDescriptions.load(false), BundledDtcDescriptions.load(false))
    }

    @Test
    fun `the two variants are different instances`() {
        assertNotSame(toyota, genericOnly)
    }

    @Test
    fun `the generic file has no manufacturer-specific codes`() {
        val parsed = BundledDtcDescriptions.parseGeneric(resourceLines("/dtc/generic.tsv"))
        for (code in parsed.keys) assertFalse("$code", Dtc(code).isManufacturerSpecific)
    }

    @Test
    fun `every generic code is a valid unique Dtc with a description`() {
        val parsed = BundledDtcDescriptions.parseGeneric(resourceLines("/dtc/generic.tsv"))
        assertTrue("generic.tsv is suspiciously small: ${parsed.size}", parsed.size > 500)
        for ((code, description) in parsed) {
            Dtc(code)
            assertTrue("$code description", description.isNotBlank() && description.length < 90)
        }
    }

    @Test
    fun `every vehicle code is a valid unique Dtc`() {
        val parsed = BundledDtcDescriptions.parseVehicle(resourceLines("/dtc/toyota-2zz.tsv"))
        assertTrue("toyota-2zz.tsv is suspiciously small: ${parsed.size}", parsed.size > 10)
        for (code in parsed.keys) Dtc(code)
    }

    @Test
    fun `every code in the vehicle file resolves to a description`() {
        val parsed = BundledDtcDescriptions.parseVehicle(resourceLines("/dtc/toyota-2zz.tsv"))
        for (code in parsed.keys) assertNotNull(code, describe(code))
    }

    // ---- spot checks of well-known codes ----

    @Test
    fun `well-known generic codes have the standard wording`() {
        assertEquals("Catalyst System Efficiency Below Threshold (Bank 1)", describe("P0420")!!.description)
        assertEquals("Mass or Volume Air Flow Circuit Range/Performance", describe("P0101")!!.description)
        assertEquals(
            "Coolant Thermostat (Coolant Temperature Below Thermostat Regulating Temperature)",
            describe("P0128")!!.description,
        )
        assertEquals("Vehicle Speed Sensor \"A\"", describe("P0500")!!.description)
        assertEquals("Lost Communication With ECM/PCM \"A\"", describe("U0100")!!.description)
        assertEquals("Cylinder 4 Misfire Detected", describe("P0304")!!.description)
        assertEquals("O2 Sensor Heater Circuit (Bank 1 Sensor 2)", describe("P0141")!!.description)
    }

    @Test
    fun `a generic code without a vehicle row has no note`() {
        assertEquals(DtcDescription("Fuel Trim Malfunction (Bank 1)", null), describe("P0170"))
    }

    @Test
    fun `generic description is kept and the 2ZZ note is attached`() {
        val lean = describe("P0171")!!
        assertEquals("System Too Lean (Bank 1)", lean.description)
        assertTrue(lean.vehicleNote!!.contains("vacuum leak"))

        val misfire = describe("P0300")!!
        assertEquals("Random/Multiple Cylinder Misfire Detected", misfire.description)
        assertNotNull(misfire.vehicleNote)
    }

    @Test
    fun `Toyota specific code has its own description and note`() {
        val vvt = describe("P1349")!!
        assertEquals("VVT System Malfunction (Bank 1)", vvt.description)
        assertTrue(vvt.vehicleNote!!.contains("oil level"))
        assertTrue(vvt.vehicleNote!!.contains("oil control valve"))
    }

    @Test
    fun `unknown standard code is null`() {
        assertNull(describe("P0999"))
        assertNull(describeGeneric("P0999"))
        assertNull(describeGeneric("U0999"))
    }

    // ---- vehicle profile ----

    @Test
    fun `without the Toyota table a Toyota code is only manufacturer-specific`() {
        assertEquals(DtcDescription(MANUFACTURER_SPECIFIC_DESCRIPTION, null), describeGeneric("P1349"))
    }

    @Test
    fun `without the Toyota table a generic code has the standard meaning and no note`() {
        assertEquals(DtcDescription("System Too Lean (Bank 1)", null), describeGeneric("P0171"))
        assertEquals(describe("P0171")!!.description, describeGeneric("P0171")!!.description)
    }

    @Test
    fun `a manufacturer code the Toyota table does not know is manufacturer-specific too`() {
        assertEquals(DtcDescription(MANUFACTURER_SPECIFIC_DESCRIPTION, null), describe("P1999"))
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
    fun `vehicle description overrides the generic one`() {
        val db = BundledDtcDescriptions(
            listOf("P0171\tSystem Too Lean (Bank 1)"),
            listOf("P0171\tToyota lean text\tcheck hoses"),
        )
        assertEquals(DtcDescription("Toyota lean text", "check hoses"), db.describe(Dtc("P0171")))
    }

    @Test
    fun `empty vehicle description falls back to generic and keeps the note`() {
        val db = BundledDtcDescriptions(
            listOf("P0171\tSystem Too Lean (Bank 1)"),
            listOf("P0171\t\tcheck hoses"),
        )
        assertEquals(DtcDescription("System Too Lean (Bank 1)", "check hoses"), db.describe(Dtc("P0171")))
    }

    @Test
    fun `vehicle description without a note has a null note`() {
        val db = BundledDtcDescriptions(emptyList(), listOf("P1349\tVVT System Malfunction\t"))
        assertEquals(DtcDescription("VVT System Malfunction", null), db.describe(Dtc("P1349")))
    }

    @Test
    fun `note without any description is rejected at construction`() {
        assertRejected("P0171") {
            BundledDtcDescriptions(emptyList(), listOf("P0171\t\tcheck hoses"))
        }
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
    }

    @Test
    fun `vehicle line with the wrong column count fails with its line number`() {
        assertRejected("toyota-2zz.tsv line 2") {
            BundledDtcDescriptions.parseVehicle(listOf("# header", "P1349\tonly two columns"))
        }
    }

    @Test
    fun `vehicle line with neither description nor note fails with its line number`() {
        assertRejected("toyota-2zz.tsv line 1") {
            BundledDtcDescriptions.parseVehicle(listOf("P1349\t\t"))
        }
    }
}
