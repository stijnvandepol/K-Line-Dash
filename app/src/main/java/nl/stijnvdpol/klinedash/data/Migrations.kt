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

/**
 * The `tracks` table exactly as Room generates it for v3, and the columns `trips` gained. SQLite
 * can add nullable columns and NOT NULL columns with a default in place, so the existing trip rows
 * are kept; the cache columns are NULL (`hasGps` 0) until the trip is analysed. Checked against
 * app/schemas/.../3.json by a test.
 */
internal val MIGRATION_2_3_STATEMENTS = listOf(
    "CREATE TABLE IF NOT EXISTS `tracks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`name` TEXT NOT NULL, `startLat` REAL NOT NULL, `startLon` REAL NOT NULL, " +
        "`startHeadingDeg` REAL NOT NULL, `startWidthM` REAL NOT NULL, `sectorsJson` TEXT NOT NULL, " +
        "`createdMs` INTEGER NOT NULL)",
    "ALTER TABLE `trips` ADD COLUMN `trackId` INTEGER",
    "ALTER TABLE `trips` ADD COLUMN `hasGps` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `trips` ADD COLUMN `movingTimeMs` INTEGER",
    "ALTER TABLE `trips` ADD COLUMN `estimatedFuelLiters` REAL",
    "ALTER TABLE `trips` ADD COLUMN `lapCount` INTEGER",
    "ALTER TABLE `trips` ADD COLUMN `bestLapMs` INTEGER",
    "CREATE INDEX IF NOT EXISTS `index_trips_trackId` ON `trips` (`trackId`)",
)

/**
 * v2 -> v3: adds the `tracks` table (start/finish and sector gates of a circuit) and, on `trips`,
 * the track a trip's laps are computed for plus a cache of its statistics (see [Trip]). Nothing is
 * rewritten and the CSV files are untouched.
 *
 * Rollback (SQLite before 3.35 cannot drop columns, and v2 ignores columns it does not know): set
 * the database version back to 2 and `DROP TABLE tracks`; the extra `trips` columns
 * stay unused and are harmless. Laps are derived from the CSV files, so nothing is lost.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_2_3_STATEMENTS.forEach { db.execSQL(it) }
    }
}
