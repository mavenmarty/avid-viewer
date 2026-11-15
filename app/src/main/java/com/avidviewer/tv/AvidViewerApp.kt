package com.avidviewer.tv

import android.app.Application
import com.avidviewer.tv.data.SecurityMonitor

/** Starts freeRASP's runtime checks before any screen opens (see [SecurityMonitor]). */
class AvidViewerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SecurityMonitor.start(this)
    }
}
