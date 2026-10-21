package nl.stijnvdpol.klinedash

import android.content.Context
import androidx.room.Room
import nl.stijnvdpol.klinedash.core.elm.ProtocolStore
import nl.stijnvdpol.klinedash.data.AppDatabase
import nl.stijnvdpol.klinedash.data.RoomProtocolStore
import nl.stijnvdpol.klinedash.data.SettingsRepository

/** Manual DI container (no Hilt: one process, a handful of singletons). */
class AppGraph(val appContext: Context) {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "klinedash.db").build()
    }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(database.settingsDao()) }
    val protocolStore: ProtocolStore by lazy { RoomProtocolStore(database.settingsDao()) }
}

val Context.appGraph: AppGraph
    get() = (applicationContext as KLineDashApp).graph
