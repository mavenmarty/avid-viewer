package com.avidviewer.tv

import android.app.Application
import com.avidviewer.tv.data.SecurityMonitor

/** Starts freeRASP's runtime checks before any screen opens (see [SecurityMonitor]). */
class AvidViewerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before any screen marks this run as "on screen": did the last run end while on screen? (1.13.1: this ran in
        // the view model, which 1.12.0 began creating after onStart, so every start reported itself as a crash.)
        com.avidviewer.tv.data.CrashLog.adoptUncleanExit(this)
        SecurityMonitor.start(this)
    }
}
