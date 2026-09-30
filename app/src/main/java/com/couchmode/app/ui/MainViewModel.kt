package com.couchmode.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.couchmode.app.daemon.DaemonClient
import com.couchmode.app.daemon.PadDevice
import com.couchmode.app.daemon.PriorityEntry
import com.couchmode.app.retroid.CompatMode
import com.couchmode.app.retroid.RetroidCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** The Retroid-compatibility setting and what it resulted in; [mode] is null until first applied. */
data class CompatState(
    val enabled: Boolean = true,
    val mode: CompatMode? = null,
    val busy: Boolean = false,
)

data class ControllersState(
    /** null until the first check completes. */
    val daemonRunning: Boolean? = null,
    val priority: List<PriorityEntry> = emptyList(),
    /** Gamepad-like devices present right now (includes ones already in [priority]). */
    val devices: List<PadDevice> = emptyList(),
    val compat: CompatState = CompatState(),
    /** User-chosen controller names, keyed by nameKey(name, id, uniq). */
    val userNames: Map<String, String> = emptyMap(),
)

private const val POLL_MS = 2000L

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val refreshNow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val prefs = application.getSharedPreferences("couchmode", android.content.Context.MODE_PRIVATE)
    private val compat = MutableStateFlow(CompatState(enabled = prefs.getBoolean(KEY_RETROID_COMPAT, true)))
    private val userNames = MutableStateFlow(loadUserNames())

    // A reorder shown immediately, until the daemon's own list catches up.
    private val optimisticPriority = MutableStateFlow<List<PriorityEntry>?>(null)
    private var saveSeq = 0  // so only the latest save's timer clears the override

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

    val state: StateFlow<ControllersState> = combine(polled, optimisticPriority, compat, userNames) { polled, optimistic, compat, names ->
        polled.copy(priority = optimistic ?: polled.priority, compat = compat, userNames = names)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ControllersState())

    private val compatLock = Mutex()

    init {
        // Once the daemon is reachable, bring it and the Retroid service into the wanted state, and
        // again whenever the set of external controllers in the list changes (a newly added pad's name
        // must be on the Retroid service's ignore list before the pad next connects).
        viewModelScope.launch {
            state.first { it.daemonRunning == true }
            state.map { externalNames(it.priority) }.distinctUntilChanged().collect { applyRetroidCompat() }
        }
    }

    /** Names of the external controllers in the list. The onboard controls are never given to the ignore list. */
    private fun externalNames(priority: List<PriorityEntry>): Set<String> =
        priority.filterNot { isOnboard(it.name) }.map { it.name }.toSet()

    /** Brings the Retroid service's ignore list in line with the current list. One run at a time. */
    private suspend fun applyRetroidCompat() = compatLock.withLock {
        val enabled = compat.value.enabled
        compat.value = compat.value.copy(busy = true)
        val owned = prefs.getStringSet(KEY_OWNED_IGNORES, emptySet()) ?: emptySet()
        val result = RetroidCompat.apply(getApplication(), enabled, externalNames(state.value.priority), owned)
        prefs.edit().putStringSet(KEY_OWNED_IGNORES, result.owned).apply()
        compat.value = CompatState(enabled = enabled, mode = result.mode, busy = false)
        refreshNow.tryEmit(Unit)
    }

    fun setRetroidCompatEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RETROID_COMPAT, enabled).apply()
        compat.value = compat.value.copy(enabled = enabled)
        viewModelScope.launch { applyRetroidCompat() }
    }

    private suspend fun load(): ControllersState {
        if (!DaemonClient.isRunning()) return ControllersState(daemonRunning = false)
        return ControllersState(
            daemonRunning = true,
            priority = runCatching { DaemonClient.priority() }.getOrDefault(emptyList()),
            devices = runCatching { DaemonClient.listDevices() }.getOrDefault(emptyList()),
        )
    }

    private fun loadUserNames(): Map<String, String> =
        prefs.all.filterKeys { it.startsWith(NAME_PREFIX) }
            .mapKeys { it.key.removePrefix(NAME_PREFIX) }
            .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
            .toMap()

    /** Gives a controller a name of the user's choosing; a blank name goes back to the default. */
    fun setUserName(key: String, name: String) {
        val trimmed = name.trim()
        prefs.edit().apply {
            if (trimmed.isEmpty()) remove(NAME_PREFIX + key) else putString(NAME_PREFIX + key, trimmed)
        }.apply()
        userNames.value = loadUserNames()
    }

    fun refresh() {
        refreshNow.tryEmit(Unit)
    }

    /** Saves a new priority list (highest first). The daemon persists it and switches sources. */
    fun setPriority(entries: List<PriorityEntry>) {
        optimisticPriority.value = entries
        val seq = ++saveSeq
        viewModelScope.launch {
            runCatching { DaemonClient.setPriority(entries) }
            refreshNow.tryEmit(Unit)
            // Give the next poll time to return the saved list, then stop overriding.
            kotlinx.coroutines.delay(POLL_MS + 500)
            if (seq == saveSeq) optimisticPriority.value = null
        }
    }

    fun addToPriority(device: PadDevice) {
        val current = state.value.priority
        if (current.any { it.matches(device) } || current.size >= MAX_PRIORITY) return
        // The daemon reports a held pad under its own identity. If it only has the Retroid
        // service's copy to go on, drop the copy's ID so the entry keeps matching the real pad.
        val id = if (isVendorCopy(device.id)) "" else device.id
        setPriority(current + PriorityEntry(device.name, id, device.uniq, connected = true, active = false))
    }

    fun removeFromPriority(entry: PriorityEntry) =
        setPriority(state.value.priority.filterNot { it.identity() == entry.identity() })

    companion object {
        const val MAX_PRIORITY = 8
        private const val KEY_RETROID_COMPAT = "retroid_compat"
        private const val KEY_OWNED_IGNORES = "retroid_owned_ignores"
        private const val NAME_PREFIX = "name:"
    }
}

/**
 * What makes a priority entry "the same entry": name, id AND uniq. Two identical pads share name and
 * id, so leaving the uniq out makes them collide (they drag together and "remove" deletes both).
 */
fun PriorityEntry.identity() = "$name|$id|$uniq"

fun PriorityEntry.matches(device: PadDevice) =
    name == device.name && (id.isEmpty() || id == device.id) && (uniq.isEmpty() || uniq == device.uniq)
