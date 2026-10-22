package nl.stijnvdpol.klinedash

import android.content.Context
import android.util.Log
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import nl.stijnvdpol.klinedash.core.diag.BundledDtcDescriptions
import nl.stijnvdpol.klinedash.core.diag.Dtc
import nl.stijnvdpol.klinedash.core.diag.DtcDescription
import nl.stijnvdpol.klinedash.core.diag.DtcDescriptions
import nl.stijnvdpol.klinedash.core.elm.ProtocolStore
import nl.stijnvdpol.klinedash.core.transport.TransportKind
import nl.stijnvdpol.klinedash.data.AlertSettingsRepository
import nl.stijnvdpol.klinedash.data.AppDatabase
import nl.stijnvdpol.klinedash.data.DashboardLayoutRepository
import nl.stijnvdpol.klinedash.data.GeneralSettingsRepository
import nl.stijnvdpol.klinedash.data.GpsSettingsRepository
import nl.stijnvdpol.klinedash.data.MIGRATION_1_2
import nl.stijnvdpol.klinedash.data.MIGRATION_2_3
import nl.stijnvdpol.klinedash.data.RoomProtocolStore
import nl.stijnvdpol.klinedash.data.SettingsRepository
import nl.stijnvdpol.klinedash.data.StartupSettingsRepository
import nl.stijnvdpol.klinedash.data.ThemeSettingsRepository
import nl.stijnvdpol.klinedash.data.TrackRepository
import nl.stijnvdpol.klinedash.data.TripSettingsRepository
import nl.stijnvdpol.klinedash.service.CrashLog
import nl.stijnvdpol.klinedash.service.ExportController
import nl.stijnvdpol.klinedash.service.LapSessions
import nl.stijnvdpol.klinedash.service.LocationHub
import nl.stijnvdpol.klinedash.service.LocationSource
import nl.stijnvdpol.klinedash.service.LocationTracker
import nl.stijnvdpol.klinedash.service.ObdServiceState
import nl.stijnvdpol.klinedash.service.SimulatedLocationSource
import nl.stijnvdpol.klinedash.service.TripAnalysisRepository
import nl.stijnvdpol.klinedash.service.TripExporter
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
private const val CRASH_DIR_NAME = "crashes"

/** Stands in when the bundled database cannot be read: codes are then shown without text. */
private object NoDtcDescriptions : DtcDescriptions {
    override fun describe(dtc: Dtc): DtcDescription? = null
}

/** Manual DI container (no Hilt: one process, a handful of singletons). */
class AppGraph(val appContext: Context) {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "klinedash.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(database.settingsDao()) }
    val protocolStore: ProtocolStore by lazy { RoomProtocolStore(database.settingsDao()) }

    /**
     * For work that belongs to the process rather than to a screen or the service: the shared GPS
     * and settings flows, the export server, background loads, boot handling. Never cancelled on
     * purpose, it ends with the process; a failing child does not cancel the others.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Uncaught exceptions of this process, installed as the crash handler by KLineDashApp. Internal
     * storage only. Under the data directory itself (a path, no disk access) rather than filesDir,
     * whose first use creates the folder and would be a main-thread disk write at startup.
     */
    val crashLog: CrashLog by lazy { CrashLog(File(appContext.applicationInfo.dataDir, CRASH_DIR_NAME)) }

    val alertSettingsRepository: AlertSettingsRepository by lazy { AlertSettingsRepository(database.settingsDao()) }
    val tripSettingsRepository: TripSettingsRepository by lazy { TripSettingsRepository(database.settingsDao()) }
    val dashboardLayoutRepository: DashboardLayoutRepository by lazy { DashboardLayoutRepository(database.settingsDao()) }
    val startupSettingsRepository: StartupSettingsRepository by lazy { StartupSettingsRepository(database.settingsDao()) }
    val generalSettingsRepository: GeneralSettingsRepository by lazy { GeneralSettingsRepository(database.settingsDao()) }

    /**
     * App-specific external storage: no permission needed, removed with the app. Null only when that
     * storage is unavailable; the internal files directory then keeps recording working.
     */
    val tripsDir: File by lazy {
        val external = appContext.getExternalFilesDir(TRIPS_DIR_NAME)
        if (external == null) Log.w(TAG, "External files directory unavailable, trips go to internal storage")
        external ?: File(appContext.filesDir, TRIPS_DIR_NAME)
    }

    val gpsSettingsRepository: GpsSettingsRepository by lazy { GpsSettingsRepository(database.settingsDao()) }
    val trackRepository: TrackRepository by lazy {
        TrackRepository(database.trackDao(), database.tripDao(), gpsSettingsRepository)
    }

    /** True while the simulated circuit replaces the device GPS: simulator transport selected and the GPS setting on. */
    val useSimulatedGps: StateFlow<Boolean> by lazy {
        combine(
            gpsSettingsRepository.settings.map { it.useSimulatedGps },
            settingsRepository.connectionSettings.map { it.kind == TransportKind.SIMULATOR },
        ) { wanted, isSimulator -> wanted && isSimulator }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)
    }

    /**
     * Position fix and GPS status for the UI and the recorder (device GPS, or the simulated circuit in
     * simulator mode). Started by ObdService once the app is visible, see ObdServiceState.setAppVisible.
     */
    val location: LocationSource by lazy {
        LocationHub(
            device = LocationTracker(appContext, applicationScope),
            simulated = SimulatedLocationSource(ObdServiceState.poller, applicationScope),
            useSimulated = useSimulatedGps,
            scope = applicationScope,
        )
    }

    /** Records trips; its sampling loop is run by ObdService. */
    val tripRecorder: TripRecorder by lazy {
        TripRecorder(
            tripsDir, database.tripDao(), tripSettingsRepository.settings, ObdServiceState.poller,
            location, trackRepository.activeTrack, tripAnalysis::refreshSummary, trackRepository::release,
        )
    }

    /** Statistics, laps and route of a recorded trip, read from its CSV. */
    val tripAnalysis: TripAnalysisRepository by lazy {
        TripAnalysisRepository(tripsDir, database.tripDao(), database.trackDao())
    }

    /** Generates the VBO, GPX and PC CSV files of a trip into the trips folder, on demand. */
    val tripExporter: TripExporter by lazy {
        TripExporter(tripsDir, database.tripDao(), tripAnalysis, tripRecorder.state)
    }

    /** Tracks (start/finish and sector gates) and lap timing. */
    val lapSessions: LapSessions by lazy {
        LapSessions(trackRepository, database.tripDao(), location, tripAnalysis, tripExporter, tripRecorder)
    }

    /** Trip list, delete and copy-to-USB for the UI. */
    val tripLibrary: TripLibrary by lazy {
        TripLibrary(database.tripDao(), tripsDir, tripRecorder.state, appContext.contentResolver)
    }

    /** Wi-Fi export of the trip files; stopped by ObdService when it ends. */
    val exportController: ExportController by lazy {
        ExportController(TripFileSource { listTripFiles(tripsDir) }, applicationScope)
    }

    /** Started on first use and parsed off the main thread; every caller awaits the same load. */
    private val dtcDescriptions: Deferred<DtcDescriptions> by lazy {
        applicationScope.async(Dispatchers.IO) {
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
        ThemeCatalogSource(loader, applicationScope).also { it.reload() }
    }
}

val Context.appGraph: AppGraph
    get() = (applicationContext as KLineDashApp).graph
