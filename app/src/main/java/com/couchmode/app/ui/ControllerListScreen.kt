package com.couchmode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.couchmode.app.daemon.PriorityEntry

private val ConnectedGreen = Color(0xFF2E9E44)

private fun PriorityEntry.key() = "$name|$id"

/** Home screen: the priority list of controllers (topmost connected one is used). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerListScreen(
    state: ControllersState,
    onReorder: (List<PriorityEntry>) -> Unit,
    onRemove: (PriorityEntry) -> Unit,
    onAddController: () -> Unit,
    onOpenDeveloperTools: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Controllers") },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Developer tools") },
                            onClick = {
                                menuOpen = false
                                onOpenDeveloperTools()
                            },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.daemonRunning == false) DaemonNotRunningCard()
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                PriorityList(state.priority, onReorder, onRemove)
                OutlinedButton(
                    onClick = onAddController,
                    enabled = state.daemonRunning == true && state.priority.size < MainViewModel.MAX_PRIORITY,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("  Add controller")
                }
            }
        }
    }
}

@Composable
private fun DaemonNotRunningCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("CouchMode service isn't running", style = MaterialTheme.typography.titleSmall)
            Text(
                "Start it with Settings → Run script as Root and pick couchmode-start.sh (see tools/).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PriorityList(
    entries: List<PriorityEntry>,
    onReorder: (List<PriorityEntry>) -> Unit,
    onRemove: (PriorityEntry) -> Unit,
) {
    // The order shown while dragging; the saved list only changes when the drag ends.
    var order by remember { mutableStateOf(entries) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    LaunchedEffect(entries) { if (dragKey == null) order = entries }

    if (order.isEmpty()) {
        Text(
            "No controllers yet. Add one below.",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    order.forEachIndexed { index, entry ->
        val key = entry.key()
        val dragging = key == dragKey
        if (index > 0) HorizontalDivider()
        ControllerRow(
            rank = index + 1,
            entry = entry,
            modifier = Modifier
                .onSizeChanged { heights[key] = it.height }
                .zIndex(if (dragging) 1f else 0f)
                .graphicsLayer { translationY = if (dragging) dragOffset else 0f },
            onRemove = { onRemove(entry) },
            dragHandle = Modifier.pointerInput(key) {
                detectDragGestures(
                    onDragStart = {
                        dragKey = key
                        dragOffset = 0f
                    },
                    onDragEnd = {
                        dragKey = null
                        dragOffset = 0f
                        if (order.map { it.key() } != entries.map { it.key() }) onReorder(order)
                    },
                    onDragCancel = {
                        dragKey = null
                        dragOffset = 0f
                        order = entries
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragOffset += amount.y
                        val i = order.indexOfFirst { it.key() == key }
                        // Swap with a neighbour once the dragged row is more than half over it.
                        val neighbour = if (dragOffset > 0) i + 1 else i - 1
                        val other = order.getOrNull(neighbour) ?: return@detectDragGestures
                        val h = (heights[other.key()] ?: 0).toFloat()
                        if (kotlin.math.abs(dragOffset) > h / 2f) {
                            order = order.toMutableList().also { java.util.Collections.swap(it, i, neighbour) }
                            dragOffset += if (dragOffset > 0) -h else h
                        }
                    },
                )
            },
        )
    }
}

@Composable
private fun ControllerRow(
    rank: Int,
    entry: PriorityEntry,
    onRemove: () -> Unit,
    dragHandle: Modifier,
    modifier: Modifier = Modifier,
) {
    val background = if (entry.active) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .alpha(if (entry.connected) 1f else 0.55f)
            .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Menu,
            contentDescription = "Drag to reorder",
            modifier = dragHandle
                .padding(8.dp)
                .size(24.dp),
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    if (entry.active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                rank.toString(),
                color = if (entry.active) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(displayName(entry.name), style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    entry.active -> "In use"
                    entry.connected -> "Connected"
                    else -> "Not connected"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .padding(horizontal = 8.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(if (entry.connected) ConnectedGreen else Color.Gray)
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "Remove from list")
        }
    }
}
