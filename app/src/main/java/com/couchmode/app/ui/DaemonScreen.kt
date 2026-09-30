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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.couchmode.app.daemon.DaemonClient
import com.couchmode.app.daemon.DaemonStatus
import com.couchmode.app.daemon.EvdevNames
import com.couchmode.app.daemon.PadDevice
import com.couchmode.app.daemon.PriorityEntry
import com.couchmode.app.retroid.RetroidMapping
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * Temporary developer screen for the app <-> daemon channel: daemon status, the
 * priority list (topmost connected controller wins), the gamepad-like devices
 * currently present, and a raw-event watcher to see what a controller sends.
 * The real priority-list UI (Phase 4: drag to reorder, add-controller picker,
 * wizard) replaces this.
 */
@Composable
fun DaemonScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf<DaemonStatus?>(null) }
    var devices by remember { mutableStateOf<List<PadDevice>>(emptyList()) }
    var priority by remember { mutableStateOf<List<PriorityEntry>>(emptyList()) }
    var watching by remember { mutableStateOf<PadDevice?>(null) }
    var watchError by remember { mutableStateOf<String?>(null) }
    val values: SnapshotStateMap<String, Int> = remember { mutableStateMapOf() }
    val context = LocalContext.current
    var retroidIgnored by remember { mutableStateOf<List<String>?>(null) }
    var retroidNote by remember { mutableStateOf("Not checked") }

    // Polls the daemon every couple of seconds so connection state stays current;
    // changing `refresh` restarts the loop for an immediate update.
    LaunchedEffect(refresh) {
        while (isActive) {
            running = DaemonClient.isRunning()
            if (running == true) {
                status = runCatching { DaemonClient.status() }.getOrNull()
                devices = runCatching { DaemonClient.listDevices() }.getOrDefault(emptyList())
                priority = runCatching { DaemonClient.priority() }.getOrDefault(emptyList())
            } else {
                status = null
                devices = emptyList()
                priority = emptyList()
            }
            delay(2000)
        }
    }

    fun savePriority(entries: List<PriorityEntry>) {
        priority = entries  // optimistic; the next poll confirms
        scope.launch {
            runCatching { DaemonClient.setPriority(entries) }
            refresh++
        }
    }

    // Collecting the flow holds the daemon-side sniff open; cancelling closes it.
    LaunchedEffect(watching) {
        values.clear()
        watchError = null
        val device = watching ?: return@LaunchedEffect
        try {
            DaemonClient.sniff(device).collect { ev -> values[EvdevNames.name(ev.type, ev.code)] = ev.value }
            watchError = "device disappeared or source changed"
        } catch (e: CancellationException) {
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
                    if (it.sourceConnected) "Daemon running. Forwarding from: ${it.source}"
                    else "Daemon running. No controller from the priority list is connected."
                } ?: "Daemon running."
            }
        )
        Button(onClick = { refresh++ }) { Text("Refresh") }

        Text("Retroid input service")
        Text(if (RetroidMapping.isInstalled(context)) "Installed (${RetroidMapping.PACKAGE})" else "Not installed")
        Text(retroidNote)
        retroidIgnored?.let { Text("Ignored devices: " + (it.ifEmpty { listOf("(none)") }.joinToString(", "))) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    retroidIgnored = RetroidMapping.ignoredDevices(context)
                    retroidNote = if (retroidIgnored == null) "Could not reach the service" else "Read OK"
                }
            }) { Text("Read list") }
            OutlinedButton(onClick = {
                scope.launch {
                    val ok = RetroidMapping.setIgnored(context, "CouchMode Virtual Gamepad", true)
                    retroidIgnored = RetroidMapping.ignoredDevices(context)
                    retroidNote = if (ok) "Added CouchMode Virtual Gamepad" else "Add failed"
                }
            }) { Text("Ignore ours") }
            OutlinedButton(onClick = {
                scope.launch {
                    val ok = RetroidMapping.setIgnored(context, "CouchMode Virtual Gamepad", false)
                    retroidIgnored = RetroidMapping.ignoredDevices(context)
                    retroidNote = if (ok) "Removed CouchMode Virtual Gamepad" else "Remove failed"
                }
            }) { Text("Stop ignoring") }
        }

        Text("Priority (top = used first)")
        if (priority.isEmpty()) Text("Empty. Add a controller below.")
        priority.forEachIndexed { index, entry ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${index + 1}. ${entry.name}" + when {
                        entry.active -> "  [in use]"
                        entry.connected -> "  [connected]"
                        else -> "  [not connected]"
                    }
                )
                Text(entry.id.ifEmpty { "any id" })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = index > 0,
                        onClick = {
                            savePriority(priority.toMutableList().also { Collections.swap(it, index, index - 1) })
                        },
                    ) { Text("Up") }
                    OutlinedButton(
                        enabled = index < priority.lastIndex,
                        onClick = {
                            savePriority(priority.toMutableList().also { Collections.swap(it, index, index + 1) })
                        },
                    ) { Text("Down") }
                    OutlinedButton(onClick = { savePriority(priority.filterIndexed { i, _ -> i != index }) }) {
                        Text("Remove")
                    }
                }
            }
        }

        Text("Controllers present")
        devices.forEach { device ->
            val inList = priority.any { it.matches(device) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(device.name + if (device.isSource) "  [in use]" else "")
                Text(device.id)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { watching = if (watching == device) null else device }) {
                        Text(if (watching == device) "Stop watching" else "Watch")
                    }
                    OutlinedButton(
                        enabled = !inList && priority.size < 8,
                        onClick = {
                            savePriority(priority + PriorityEntry(device.name, device.id, device.uniq, true, false))
                        },
                    ) { Text(if (inList) "In priority list" else "Add to priority") }
                }
            }
        }

        if (watching != null) {
            Text("Watching: ${watching?.name} (${watching?.id}) — press buttons, move sticks")
            watchError?.let { Text("Watch ended: $it") }
            values.entries.sortedBy { it.key }.forEach { (name, value) -> Text("$name = $value") }
        }
    }
}
