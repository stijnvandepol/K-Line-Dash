package nl.stijnvdpol.klinedash

import android.app.Application
import android.os.StrictMode
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "KLineDashApp"

class KLineDashApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
        graph = AppGraph(this)
        // Write the crash to the local log, then let Android show its crash dialog and end the process.
        Thread.setDefaultUncaughtExceptionHandler(
            graph.crashLog.asUncaughtExceptionHandler(Thread.getDefaultUncaughtExceptionHandler()),
        )
        // The settings and themes the first frame needs are read now, in parallel and off the main thread.
        val settingsRead = graph.warmUp()
        // A trip cut off by a power loss (ACC off) still has no end time: repair it from its file,
        // once the settings are read, so it does not compete with them for the first database open.
        val failureLog = CoroutineExceptionHandler { _, e -> Log.e(TAG, "Recovering unfinished trips failed", e) }
        graph.applicationScope.launch(Dispatchers.IO + failureLog) {
            settingsRead.join()
            graph.tripRecorder.recoverUnfinished()
        }
    }

    /** Debug builds only: log disk and network use on the main thread, which makes the dashboard stutter. */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
    }
}
