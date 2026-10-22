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
import nl.stijnvdpol.klinedash.data.AlertSettingsRepository
import nl.stijnvdpol.klinedash.data.AppDatabase
import nl.stijnvdpol.klinedash.data.DashboardLayoutRepository
import nl.stijnvdpol.klinedash.data.MIGRATION_1_2
import nl.stijnvdpol.klinedash.data.RoomProtocolStore
import nl.stijnvdpol.klinedash.data.SettingsRepository
import nl.stijnvdpol.klinedash.data.StartupSettingsRepository
import nl.stijnvdpol.klinedash.data.ThemeSettingsRepository
import nl.stijnvdpol.klinedash.data.TripSettingsRepository
import nl.stijnvdpol.klinedash.service.ExportController
import nl.stijnvdpol.klinedash.service.ObdServiceState
import nl.stijnvdpol.klinedash.service.TripLibrary
import nl.stijnvdpol.klinedash.service.TripRecorder
import nl.stijnvdpol.klinedash.service.listTripFiles
import nl.stijnvdpol.klinedash.ui.theme.ThemeCatalogSource
import nl.stijnvdpol.klinedash.ui.theme.ThemeLoader
import nl.stijnvdpol.klinedash.core.export.TripFileSource
import java.io.File

private const val TAG = "AppGraph"
private const val TRIPS_DIR_NAME = "trips"
private const val THEMES_DIR_NAME = "themes"

/** Stands in when the bundled database cannot be read: codes are then shown without text. */
private object NoDtcDescriptions : DtcDescriptions {
    override fun describe(dtc: Dtc): DtcDescription? = null
}

/** Manual DI container (no Hilt: one process, a handful of singletons). */
class AppGraph(val appContext: Context) {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "klinedash.db")
            .addMigrations(MIGRATION_1_2)
            .build()
    }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(database.settingsDao()) }
    val protocolStore: ProtocolStore by lazy { RoomProtocolStore(database.settingsDao()) }

    private val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val alertSettingsRepository: AlertSettingsRepository by lazy { AlertSettingsRepository(database.settingsDao()) }
    val tripSettingsRepository: TripSettingsRepository by lazy { TripSettingsRepository(database.settingsDao()) }
    val dashboardLayoutRepository: DashboardLayoutRepository by lazy { DashboardLayoutRepository(database.settingsDao()) }
    val startupSettingsRepository: StartupSettingsRepository by lazy { StartupSettingsRepository(database.settingsDao()) }

    /**
     * App-specific external storage: no permission needed, removed with the app. Null only when that
     * storage is unavailable; the internal files directory then keeps recording working.
     */
    val tripsDir: File by lazy {
        val external = appContext.getExternalFilesDir(TRIPS_DIR_NAME)
        if (external == null) Log.w(TAG, "External files directory unavailable, trips go to internal storage")
        external ?: File(appContext.filesDir, TRIPS_DIR_NAME)
    }

    /** Records trips; its sampling loop is run by ObdService. */
    val tripRecorder: TripRecorder by lazy {
        TripRecorder(tripsDir, database.tripDao(), tripSettingsRepository.settings, ObdServiceState.poller)
    }

    /** Trip list, delete and copy-to-USB for the UI. */
    val tripLibrary: TripLibrary by lazy {
        TripLibrary(database.tripDao(), tripsDir, tripRecorder.state, appContext.contentResolver)
    }

    /** Wi-Fi export of the trip files; stopped by ObdService when it ends. */
    val exportController: ExportController by lazy {
        ExportController(TripFileSource { listTripFiles(tripsDir) }, exportScope)
    }

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

    val themeSettingsRepository: ThemeSettingsRepository by lazy { ThemeSettingsRepository(database.settingsDao()) }

    /**
     * Where users put their own theme files; created on first use, by the theme loader off the main
     * thread. Internal storage when no external storage exists.
     */
    private val themesDir: File by lazy {
        val dir = appContext.getExternalFilesDir(THEMES_DIR_NAME) ?: File(appContext.filesDir, THEMES_DIR_NAME)
        if (!dir.isDirectory && !dir.mkdirs()) Log.w(TAG, "Could not create the themes folder")
        dir
    }

    /** Built-in themes (assets) and the user's files, loaded off the main thread right away. */
    val themeCatalog: ThemeCatalogSource by lazy {
        val loader = ThemeLoader(
            readBuiltIn = { id -> appContext.assets.open("$THEMES_DIR_NAME/$id.json").bufferedReader().use { it.readText() } },
            userDir = { themesDir },
        )
        ThemeCatalogSource(loader, loadScope).also { it.reload() }
    }
}

val Context.appGraph: AppGraph
    get() = (applicationContext as KLineDashApp).graph
