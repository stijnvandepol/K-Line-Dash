package nl.stijnvdpol.klinedash.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Exactly the SQL Room generates for the v2 schema (checked against app/schemas/.../2.json by a test). */
internal val MIGRATION_1_2_STATEMENTS = listOf(
    "CREATE TABLE IF NOT EXISTS `trips` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`startMs` INTEGER NOT NULL, `endMs` INTEGER, `fileName` TEXT NOT NULL, " +
        "`sampleCount` INTEGER NOT NULL, `distanceKm` REAL NOT NULL, `maxRpm` REAL, " +
        "`maxSpeedKmh` REAL, `maxCoolantC` REAL)",
    "CREATE INDEX IF NOT EXISTS `index_trips_startMs` ON `trips` (`startMs`)",
)

/**
 * v1 -> v2: adds the `trips` table (index of the recorded trip CSV files). The `settings` table is
 * untouched.
 *
 * Rollback (Room cannot downgrade by itself, and v1 never reads the table): `DROP INDEX
 * index_trips_startMs; DROP TABLE trips;` and set the database version back to 1. That only loses
 * the trip index; the CSV files stay in the app's trips folder and can still be exported.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_1_2_STATEMENTS.forEach { db.execSQL(it) }
    }
}
