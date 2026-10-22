package nl.stijnvdpol.klinedash.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Generic key/value row; typed settings are mapped on top of it in [SettingsRepository]. */
@Entity(tableName = "settings")
data class SettingEntry(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntry>>

    @Query("SELECT * FROM settings")
    suspend fun getAll(): List<SettingEntry>

    @Query("SELECT * FROM settings WHERE `key` = :key")
    suspend fun get(key: String): SettingEntry?

    @Upsert
    suspend fun upsert(entry: SettingEntry)

    @Query("DELETE FROM settings WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Database(entities = [SettingEntry::class, Trip::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun tripDao(): TripDao
}
