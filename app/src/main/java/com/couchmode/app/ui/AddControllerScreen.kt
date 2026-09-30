package com.couchmode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.couchmode.app.daemon.PadDevice

/** Lists controllers that are connected right now; tapping one adds it to the priority list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddControllerScreen(
    state: ControllersState,
    onPick: (PadDevice) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The Retroid service's re-mapped copies of external pads are hidden; see isVendorCopy().
    val present = state.devices.filterNot { isVendorCopy(it.id) }
    val available = present.filterNot { device -> state.priority.any { it.matches(device) } }
    val alreadyAdded = present.filter { device -> state.priority.any { it.matches(device) } }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Select controller") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                if (available.isEmpty()) {
                    Text(
                        "No new controllers found. Connect one (Bluetooth, USB or a 2.4 GHz dongle), " +
                            "wake it, and it will show up here.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                available.forEachIndexed { index, device ->
                    if (index > 0) HorizontalDivider()
                    DeviceRow(device, added = false, modifier = Modifier.clickable { onPick(device) })
                }
                if (alreadyAdded.isNotEmpty()) {
                    if (available.isNotEmpty()) HorizontalDivider()
                    Text(
                        "Already added",
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    alreadyAdded.forEachIndexed { index, device ->
                        if (index > 0) HorizontalDivider()
                        DeviceRow(device, added = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: PadDevice, added: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (added) 0.55f else 1f)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(displayName(device.name), style = MaterialTheme.typography.bodyLarge)
            Text(
                shortId(device.id),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (added) {
            Icon(Icons.Default.Check, contentDescription = "Already added")
        } else {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2E9E44))
            )
        }
    }
}
