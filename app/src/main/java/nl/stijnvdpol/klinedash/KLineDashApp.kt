package nl.stijnvdpol.klinedash

import android.app.Application

class KLineDashApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
