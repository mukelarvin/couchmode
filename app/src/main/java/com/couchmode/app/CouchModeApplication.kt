package com.couchmode.app

import android.app.Application
import com.couchmode.app.shizuku.InputReader

class CouchModeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Registered once, app-wide, rather than per-Activity — Shizuku's
        // listeners need to survive Activity recreation.
        InputReader.registerLifecycleListeners()
    }
}
