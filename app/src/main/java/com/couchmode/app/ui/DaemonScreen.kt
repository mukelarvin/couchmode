package com.couchmode.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.couchmode.app.daemon.DaemonClient
import com.couchmode.app.daemon.DaemonStatus
import com.couchmode.app.daemon.EvdevNames
import com.couchmode.app.daemon.PadDevice
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateMapOf

/**
 * Temporary developer screen for the app <-> daemon channel: shows daemon
 * status, lists gamepad-like devices, lets you watch one device's raw events
 * (to see what a controller actually sends) and switch the forwarded source.
 * The real priority-list UI (Phase 4) replaces this.
 */
@Composable
fun DaemonScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf<DaemonStatus?>(null) }
    var devices by remember { mutableStateOf<List<PadDevice>>(emptyList()) }
    var watching by remember { mutableStateOf<String?>(null) }
    var watchError by remember { mutableStateOf<String?>(null) }
    val values: SnapshotStateMap<String, Int> = remember { mutableStateMapOf() }

    LaunchedEffect(refresh) {
        running = DaemonClient.isRunning()
        if (running == true) {
            status = runCatching { DaemonClient.status() }.getOrNull()
            devices = runCatching { DaemonClient.listDevices() }.getOrDefault(emptyList())
        } else {
            status = null
            devices = emptyList()
        }
    }

    // Collecting the flow holds the daemon-side sniff open; cancelling closes it.
    LaunchedEffect(watching) {
        values.clear()
        watchError = null
        val name = watching ?: return@LaunchedEffect
        try {
            DaemonClient.sniff(name).collect { ev -> values[EvdevNames.name(ev.type, ev.code)] = ev.value }
            watchError = "device disappeared"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            watchError = e.message ?: e.javaClass.simpleName
        }
    }

    Column(
        modifier = modifier
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("CouchMode — daemon link")
        Text(
            when (running) {
                null -> "Checking daemon…"
                false -> "Daemon not running. Start it (see tools/) and refresh."
                true -> status?.let {
                    "Daemon running. Source: ${it.source} (${if (it.sourceConnected) "connected" else "not found"})"
                } ?: "Daemon running."
            }
        )
        Button(onClick = { refresh++ }) { Text("Refresh") }

        devices.forEach { device ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(device.name + if (device.isSource) "  [source]" else "")
                Text(device.id)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { watching = if (watching == device.name) null else device.name }) {
                        Text(if (watching == device.name) "Stop watching" else "Watch")
                    }
                    OutlinedButton(
                        enabled = !device.isSource,
                        onClick = {
                            scope.launch {
                                runCatching { DaemonClient.setSource(device.name) }
                                refresh++
                            }
                        },
                    ) { Text("Use as source") }
                }
            }
        }

        if (watching != null) {
            Text("Watching: $watching — press buttons, move sticks")
            watchError?.let { Text("Watch ended: $it") }
            values.entries.sortedBy { it.key }.forEach { (name, value) -> Text("$name = $value") }
        }
    }
}
