package com.couchmode.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.couchmode.app.ui.CouchModeTheme
import com.couchmode.app.ui.DaemonScreen

// Temporary: shows the app <-> daemon link (see PLAN.md). The real priority-list
// screen (Phase 4) replaces this — see spec.md, "App UI". The Shizuku Phase 2
// screen that used to live here is on hold along with the rest of that path.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CouchModeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Scaffold { innerPadding -> DaemonScreen(Modifier.padding(innerPadding)) }
                }
            }
        }
    }
}
