package nl.stijnvdpol.klinedash

import android.content.Context
import android.util.Log
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import nl.stijnvdpol.klinedash.core.diag.BundledDtcDescriptions
import nl.stijnvdpol.klinedash.core.diag.Dtc
import nl.stijnvdpol.klinedash.core.diag.DtcDescription
import nl.stijnvdpol.klinedash.core.diag.DtcDescriptions
import nl.stijnvdpol.klinedash.core.elm.ProtocolStore
import nl.stijnvdpol.klinedash.data.AppDatabase
import nl.stijnvdpol.klinedash.data.RoomProtocolStore
import nl.stijnvdpol.klinedash.data.SettingsRepository

private const val TAG = "AppGraph"

/** Stands in when the bundled database cannot be read: codes are then shown without text. */
private object NoDtcDescriptions : DtcDescriptions {
    override fun describe(dtc: Dtc): DtcDescription? = null
}

/** Manual DI container (no Hilt: one process, a handful of singletons). */
class AppGraph(val appContext: Context) {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "klinedash.db").build()
    }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(database.settingsDao()) }
    val protocolStore: ProtocolStore by lazy { RoomProtocolStore(database.settingsDao()) }

    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Started on first use and parsed off the main thread; every caller awaits the same load. */
    private val dtcDescriptions: Deferred<DtcDescriptions> by lazy {
        loadScope.async {
            try {
                BundledDtcDescriptions.load()
            } catch (e: Exception) {
                Log.e(TAG, "Could not load the bundled trouble code descriptions", e)
                NoDtcDescriptions
            }
        }
    }

    suspend fun awaitDtcDescriptions(): DtcDescriptions = dtcDescriptions.await()
}

val Context.appGraph: AppGraph
    get() = (applicationContext as KLineDashApp).graph
