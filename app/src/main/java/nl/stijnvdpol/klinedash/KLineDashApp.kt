package nl.stijnvdpol.klinedash

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "KLineDashApp"

class KLineDashApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // A trip cut off by a power loss (ACC off) still has no end time: repair it from its file.
        val failureLog = CoroutineExceptionHandler { _, e -> Log.e(TAG, "Recovering unfinished trips failed", e) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch(failureLog) {
            graph.tripRecorder.recoverUnfinished()
        }
    }
}
