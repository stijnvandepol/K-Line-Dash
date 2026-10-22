package nl.stijnvdpol.klinedash.data

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v2 to v3 migration against the schema file Room exported for v3 (app/schemas/.../3.json), like
 * MigrationSchemaTest does for v1 to v2: Room's own migration test needs a device, so the JVM checks
 * that the SQL says exactly what the exported schema says. SQLite appends a column added with
 * ALTER TABLE at the end of the table, so the v3 CREATE statement of `trips` must be the v2 one
 * with the added column definitions appended in migration order.
 */
class MigrationV3Test {
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

    private val addedColumns = MIGRATION_2_3_STATEMENTS
        .filter { it.startsWith("ALTER TABLE `trips` ADD COLUMN ") }
        .map { it.removePrefix("ALTER TABLE `trips` ADD COLUMN ") }

    @Test
    fun migrationGoesFromVersion2To3() {
        assertEquals(2, MIGRATION_2_3.startVersion)
        assertEquals(3, MIGRATION_2_3.endVersion)
    }

    @Test
    fun tripsGainsExactlyTheColumnsOfTheExportedV3Schema() {
        val v2 = createSql(2, "trips")
        assertTrue(v2.endsWith(")"))
        val expectedV3 = v2.removeSuffix(")") + addedColumns.joinToString(separator = "") { ", $it" } + ")"
        assertEquals(expectedV3, createSql(3, "trips"))
        assertEquals("six columns: track, GPS flag, moving time, fuel, lap count, best lap", 6, addedColumns.size)
    }

    @Test
    fun theTracksTableIsCreatedAsRoomWouldCreateIt() {
        assertTrue(createSql(3, "tracks") in MIGRATION_2_3_STATEMENTS)
    }

    @Test
    fun theNewIndexMatchesAndOldTablesAreUntouched() {
        val newIndexes = indexSql(3, "trips") - indexSql(2, "trips").toSet()
        assertEquals(newIndexes, MIGRATION_2_3_STATEMENTS.filter { it.startsWith("CREATE INDEX") })
        assertEquals(createSql(2, "settings"), createSql(3, "settings"))
        assertEquals(1, MIGRATION_2_3_STATEMENTS.count { it.startsWith("CREATE TABLE") })
    }

    @Test
    fun aNotNullColumnIsAddedWithItsDefaultSoExistingRowsStayValid() {
        val notNull = addedColumns.filter { "NOT NULL" in it }
        assertEquals(listOf("`hasGps` INTEGER NOT NULL DEFAULT 0"), notNull)
    }
}
