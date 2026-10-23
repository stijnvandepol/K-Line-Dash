package nl.stijnvdpol.klinedash.data

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v3 to v4 migration against the schema file Room exported for v4 (app/schemas/.../4.json), the
 * way MigrationV3Test does for v3: the JVM checks that the SQL says exactly what the exported schema says.
 */
class MigrationV4Test {
    private val schemaDir = File("schemas").listFiles { f -> f.isDirectory }!!.single()

    private fun entity(version: Int, table: String): JSONObject {
        val entities = JSONObject(File(schemaDir, "$version.json").readText()).getJSONObject("database").getJSONArray("entities")
        return (0 until entities.length()).map { entities.getJSONObject(it) }.first { it.getString("tableName") == table }
    }

    private fun createSql(version: Int, table: String) = entity(version, table).getString("createSql").replace("\${TABLE_NAME}", table)

    private fun indexSql(version: Int, table: String): List<String> {
        val indices = entity(version, table).getJSONArray("indices")
        return (0 until indices.length()).map { indices.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", table) }
    }

    private val addedColumns = MIGRATION_3_4_STATEMENTS
        .filter { it.startsWith("ALTER TABLE `trips` ADD COLUMN ") }
        .map { it.removePrefix("ALTER TABLE `trips` ADD COLUMN ") }

    @Test
    fun migrationGoesFromVersion3To4() {
        assertEquals(3, MIGRATION_3_4.startVersion)
        assertEquals(4, MIGRATION_3_4.endVersion)
    }

    @Test
    fun tripsGainsExactlyTheColumnOfTheExportedV4Schema() {
        val v3 = createSql(3, "trips")
        assertTrue(v3.endsWith(")"))
        val expectedV4 = v3.removeSuffix(")") + addedColumns.joinToString(separator = "") { ", $it" } + ")"
        assertEquals(expectedV4, createSql(4, "trips"))
        assertEquals(listOf("`lapTimingAfterMs` INTEGER"), addedColumns)
    }

    @Test
    fun theColumnIsNullableSoExistingTripsKeepTimingTheWholeTrip() {
        assertTrue(addedColumns.none { "NOT NULL" in it })
    }

    @Test
    fun everythingElseIsUntouched() {
        assertEquals(indexSql(3, "trips"), indexSql(4, "trips"))
        assertEquals(createSql(3, "tracks"), createSql(4, "tracks"))
        assertEquals(createSql(3, "settings"), createSql(4, "settings"))
        assertEquals(1, MIGRATION_3_4_STATEMENTS.size)
    }
}
