package com.couchmode.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couchmode.app.daemon.DaemonClient
import com.couchmode.app.daemon.PadDevice
import com.couchmode.app.daemon.PriorityEntry
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class ControllersState(
    /** null until the first check completes. */
    val daemonRunning: Boolean? = null,
    val priority: List<PriorityEntry> = emptyList(),
    /** Gamepad-like devices present right now (includes ones already in [priority]). */
    val devices: List<PadDevice> = emptyList(),
)

private const val POLL_MS = 2000L

class MainViewModel : ViewModel() {
    private val refreshNow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // A reorder shown immediately, until the daemon's own list catches up.
    private val optimisticPriority = MutableStateFlow<List<PriorityEntry>?>(null)

    private val polled: StateFlow<ControllersState> = flow {
        while (true) {
            emit(load())
            withTimeoutOrNull(POLL_MS) { refreshNow.first() }
        }
    }.stateIn(
        scope = viewModelScope,
        // Only poll while a screen is actually collecting (keeps the socket quiet in the background).
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = ControllersState(),
    )

    val state: StateFlow<ControllersState> = combine(polled, optimisticPriority) { polled, optimistic ->
        if (optimistic != null) polled.copy(priority = optimistic) else polled
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ControllersState())

    private suspend fun load(): ControllersState {
        if (!DaemonClient.isRunning()) return ControllersState(daemonRunning = false)
        return ControllersState(
            daemonRunning = true,
            priority = runCatching { DaemonClient.priority() }.getOrDefault(emptyList()),
            devices = runCatching { DaemonClient.listDevices() }.getOrDefault(emptyList()),
        )
    }

    fun refresh() {
        refreshNow.tryEmit(Unit)
    }

    /** Saves a new priority list (highest first). The daemon persists it and switches sources. */
    fun setPriority(entries: List<PriorityEntry>) {
        optimisticPriority.value = entries
        viewModelScope.launch {
            runCatching { DaemonClient.setPriority(entries) }
            refreshNow.tryEmit(Unit)
            // Give the next poll time to return the saved list, then stop overriding.
            kotlinx.coroutines.delay(POLL_MS + 500)
            optimisticPriority.value = null
        }
    }

    fun addToPriority(device: PadDevice) {
        val current = state.value.priority
        if (current.any { it.matches(device) } || current.size >= MAX_PRIORITY) return
        setPriority(current + PriorityEntry(device.name, device.id, connected = true, active = false))
    }

    fun removeFromPriority(entry: PriorityEntry) =
        setPriority(state.value.priority.filterNot { it.name == entry.name && it.id == entry.id })

    companion object {
        const val MAX_PRIORITY = 8
    }
}

fun PriorityEntry.matches(device: PadDevice) = name == device.name && (id.isEmpty() || id == device.id)
