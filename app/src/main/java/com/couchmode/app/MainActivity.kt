package com.couchmode.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.couchmode.app.shizuku.InputReader
import com.couchmode.app.shizuku.ShizukuState
import com.couchmode.app.ui.CouchModeTheme

// Phase 2 scope: this screen only proves the Shizuku UserService round-trip
// works (see PLAN.md). The real priority-list screen (Phase 4) replaces this
// entirely — see spec.md, "App UI".
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CouchModeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ShizukuStatusScreen()
                }
            }
        }
    }
}

@Composable
private fun ShizukuStatusScreen() {
    var state by remember { mutableStateOf<ShizukuState>(ShizukuState.Unavailable) }

    DisposableEffect(Unit) {
        InputReader.setListener { newState -> state = newState }
        onDispose { InputReader.setListener(null) }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("CouchMode — Phase 2 check")
            Text(describeState(state))
            Button(onClick = { InputReader.requestPermission() }) {
                Text("Connect via Shizuku")
            }
        }
    }
}

private fun describeState(state: ShizukuState): String = when (state) {
    is ShizukuState.Unavailable -> "Shizuku not available — is it installed and running?"
    is ShizukuState.Available -> "Shizuku available — tap to request permission"
    is ShizukuState.PermissionDenied -> "Permission denied"
    is ShizukuState.Connected -> "Connected — remote process pid ${state.remotePid}"
}

@Preview(showBackground = true)
@Composable
private fun ShizukuStatusPreview() {
    CouchModeTheme {
        ShizukuStatusScreen()
    }
}
