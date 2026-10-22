package nl.stijnvdpol.klinedash.data

import java.io.File
import nl.stijnvdpol.klinedash.core.alert.AlertKind
import nl.stijnvdpol.klinedash.core.alert.AlertSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Room's MigrationTestHelper needs an instrumented test on a device. What can be checked on the
 * JVM is that the migration SQL is exactly what Room generated for v2 (it validates the table
 * layout against that when the database opens) and that v1 stays untouched.
 */
class MigrationSchemaTest {
    private val schemaDir = File("schemas").listFiles { f -> f.isDirectory }!!.single()

    /** The createSql lines of the schema file, with ${TABLE_NAME} filled in from the entity they belong to. */
    private fun createStatements(version: Int): List<String> {
        var table = ""
        val statements = ArrayList<String>()
        for (line in File(schemaDir, "$version.json").readLines()) {
            Regex("\"tableName\": \"([A-Za-z_]+)\"").find(line)?.let { table = it.groupValues[1] }
            Regex("\"createSql\": \"(.*)\"").find(line)?.let { statements += it.groupValues[1].replace("\${TABLE_NAME}", table) }
        }
        return statements
    }

    @Test
    fun migrationCreatesExactlyWhatSchemaV2AddsToV1() {
        val v1 = createStatements(1)
        val v2 = createStatements(2)
        assertEquals("v1 only has the settings table", 1, v1.size)
        assertTrue("v2 keeps the v1 table unchanged", v2.containsAll(v1))
        assertEquals(v2 - v1.toSet(), MIGRATION_1_2_STATEMENTS)
    }

    @Test
    fun migrationGoesFromVersion1To2() {
        assertEquals(1, MIGRATION_1_2.startVersion)
        assertEquals(2, MIGRATION_1_2.endVersion)
    }
}

class AlertSettingsPersistenceTest {
    @Test
    fun missingRowsGiveTheDefaults() {
        assertEquals(AlertSettings(), decodeAlertSettings(emptyMap()))
    }

    @Test
    fun settingsSurviveAnEncodeDecodeRoundTrip() {
        val custom = AlertSettings(
            enabled = setOf(AlertKind.COOLANT_HIGH, AlertKind.MIL_ON),
            withSound = emptySet(),
            coolantCautionC = 99.5,
            coolantDangerC = 105.0,
            voltageRunningLow = 13.0,
            voltageRunningHigh = 14.9,
            voltageEngineOffLow = 11.8,
        )
        val rows = encodeAlertSettings(custom).mapValues { checkNotNull(it.value) }
        assertEquals(custom, decodeAlertSettings(rows))
    }

    @Test
    fun unreadableValuesFallBackToTheDefaultPerField() {
        val rows = mapOf(
            AlertSettingsKeys.COOLANT_CAUTION to "hot",
            AlertSettingsKeys.COOLANT_DANGER to "NaN",
            AlertSettingsKeys.ENABLED to "COOLANT_HIGH,FROM_THE_FUTURE",
        )
        val decoded = decodeAlertSettings(rows)
        assertEquals(AlertSettings().coolantCautionC, decoded.coolantCautionC, 0.0)
        assertEquals(AlertSettings().coolantDangerC, decoded.coolantDangerC, 0.0)
        assertEquals(setOf(AlertKind.COOLANT_HIGH), decoded.enabled)
    }

    @Test
    fun equalSetsAlwaysEncodeToTheSameRow() {
        val a = AlertSettings(enabled = setOf(AlertKind.MIL_ON, AlertKind.COOLANT_HIGH))
        val b = AlertSettings(enabled = setOf(AlertKind.COOLANT_HIGH, AlertKind.MIL_ON))
        assertEquals(encodeAlertSettings(a), encodeAlertSettings(b))
    }
}
