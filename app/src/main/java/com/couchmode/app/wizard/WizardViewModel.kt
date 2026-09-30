package com.couchmode.app.wizard

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couchmode.app.daemon.DaemonClient
import com.couchmode.app.daemon.PadDevice
import com.couchmode.app.daemon.PriorityEntry
import com.couchmode.app.daemon.RawEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where on a generic controller a button sits. Positions, not letters: "A" is a different place on an Xbox and a Switch pad. */
enum class Spot {
    SOUTH, EAST, WEST, NORTH,
    LEFT_BUMPER, RIGHT_BUMPER, LEFT_TRIGGER, RIGHT_TRIGGER,
    SELECT, START, HOME, LEFT_STICK, RIGHT_STICK,
}

/** One question of the wizard: "press the button at [spot]"; its answer becomes the canonical evdev code [canonical]. */
data class WizardStep(val canonical: Int, val spot: Spot, val prompt: String, val hint: String)

// Canonical evdev button codes (linux/input-event-codes.h). These are what the virtual gamepad emits,
// so they are what emulators see, whatever layout the physical controller has.
private const val BTN_SOUTH = 304
private const val BTN_EAST = 305
private const val BTN_NORTH = 307
private const val BTN_WEST = 308
private const val BTN_TL = 310
private const val BTN_TR = 311
private const val BTN_TL2 = 312
private const val BTN_TR2 = 313
private const val BTN_SELECT = 314
private const val BTN_START = 315
private const val BTN_MODE = 316
private const val BTN_THUMBL = 317
private const val BTN_THUMBR = 318

val WIZARD_STEPS = listOf(
    WizardStep(BTN_SOUTH, Spot.SOUTH, "Press the bottom face button", "The lowest of the four buttons on the right"),
    WizardStep(BTN_EAST, Spot.EAST, "Press the right face button", "The rightmost of the four buttons"),
    WizardStep(BTN_WEST, Spot.WEST, "Press the left face button", "The leftmost of the four buttons"),
    WizardStep(BTN_NORTH, Spot.NORTH, "Press the top face button", "The highest of the four buttons"),
    WizardStep(BTN_TL, Spot.LEFT_BUMPER, "Press the left bumper", "The shoulder button on the left, nearest the top"),
    WizardStep(BTN_TR, Spot.RIGHT_BUMPER, "Press the right bumper", "The shoulder button on the right, nearest the top"),
    WizardStep(BTN_TL2, Spot.LEFT_TRIGGER, "Press the left trigger", "Skip this if your triggers are analog"),
    WizardStep(BTN_TR2, Spot.RIGHT_TRIGGER, "Press the right trigger", "Skip this if your triggers are analog"),
    WizardStep(BTN_SELECT, Spot.SELECT, "Press Select (Back)", "The small button just left of center"),
    WizardStep(BTN_START, Spot.START, "Press Start (Menu)", "The small button just right of center"),
    WizardStep(BTN_MODE, Spot.HOME, "Press the Home button", "The button in the middle, usually a logo"),
    WizardStep(BTN_THUMBL, Spot.LEFT_STICK, "Click in the left stick", "Push the left stick straight down"),
    WizardStep(BTN_THUMBR, Spot.RIGHT_STICK, "Click in the right stick", "Push the right stick straight down"),
)

const val STEP_SECONDS = 10

data class WizardState(
    val index: Int = 0,
    /** canonical code -> raw evdev code the controller sent for it */
    val captured: Map<Int, Int> = emptyMap(),
    val secondsLeft: Int = STEP_SECONDS,
    /** A press was just accepted; shown briefly before moving on. */
    val justCaptured: Boolean = false,
    val notice: String? = null,
    val finished: Boolean = false,
    /** Null while saving; true once the daemon has the map; false if nothing was captured. */
    val saved: Boolean? = null,
    val error: String? = null,
) {
    val step: WizardStep get() = WIZARD_STEPS[index.coerceIn(0, WIZARD_STEPS.lastIndex)]
}

/**
 * Walks through [WIZARD_STEPS], listening to one controller's raw buttons through the daemon, and saves
 * the result as that controller's button map (raw code -> canonical code). While it listens the daemon
 * stops forwarding that controller, so presses don't click through the app or reach a game.
 */
class WizardViewModel : ViewModel() {
    private val _state = MutableStateFlow(WizardState())
    val state: StateFlow<WizardState> = _state.asStateFlow()

    private var entry: PriorityEntry? = null
    private var kind = "raw"
    private var listenJob: Job? = null
    private var stepJob: Job? = null
    private var stepStartedAt = 0L

    /** Starts (or restarts) the wizard for [entry]. Safe to call again for the same entry while it runs. */
    fun start(entry: PriorityEntry) {
        val same = this.entry?.let { it.name == entry.name && it.id == entry.id && it.uniq == entry.uniq } == true
        if (same && listenJob?.isActive == true) return
        cancel()
        this.entry = entry
        _state.value = WizardState()
        listenJob = viewModelScope.launch {
            try {
                DaemonClient.sniff(PadDevice(entry.name, entry.id, false, entry.uniq), mute = true, onKind = { kind = it })
                    .collect { onEvent(it) }
                _state.update { if (it.finished) it else it.copy(error = "The controller disconnected.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = when {
                    e.message?.contains("notfound") == true -> "That controller isn't connected. Wake it or plug it in, then try again."
                    else -> e.message ?: "Lost contact with the CouchMode service."
                }
                _state.update { if (it.finished) it else it.copy(error = message) }
            }
        }
        beginStep()
    }

    private fun beginStep() {
        stepStartedAt = SystemClock.elapsedRealtime()
        stepJob?.cancel()
        stepJob = viewModelScope.launch {
            for (left in STEP_SECONDS downTo 1) {
                _state.update { it.copy(secondsLeft = left) }
                delay(1000)
            }
            skip()  // Nothing pressed in time: a controller without this button shouldn't block the wizard.
        }
    }

    private fun onEvent(ev: RawEvent) {
        if (ev.type != 1 || ev.value != 1) return  // key presses only (not releases, repeats or axes)
        val s = _state.value
        if (s.finished || s.justCaptured || s.error != null) return
        // Ignore anything that arrives right as a step starts (the tail of the previous press).
        if (SystemClock.elapsedRealtime() - stepStartedAt < 400) return
        if (ev.code in s.captured.values) {
            _state.update { it.copy(notice = "That button is already used. Try another.") }
            return
        }
        stepJob?.cancel()
        _state.update { it.copy(captured = it.captured + (it.step.canonical to ev.code), justCaptured = true, notice = null) }
        viewModelScope.launch {
            delay(450)
            advance()
        }
    }

    private fun advance() {
        val next = _state.value.index + 1
        if (next >= WIZARD_STEPS.size) {
            finish()
        } else {
            _state.update { it.copy(index = next, secondsLeft = STEP_SECONDS, justCaptured = false, notice = null) }
            beginStep()
        }
    }

    fun skip() {
        if (_state.value.justCaptured || _state.value.finished) return
        advance()
    }

    /** Goes back one question, forgetting that answer. */
    fun back() {
        val s = _state.value
        if (s.index == 0 || s.finished) return
        val previous = s.index - 1
        _state.update {
            it.copy(
                index = previous,
                captured = it.captured - WIZARD_STEPS[previous].canonical,
                justCaptured = false,
                notice = null,
            )
        }
        beginStep()
    }

    /** Asks the current question again from scratch. */
    fun retry() {
        val s = _state.value
        if (s.finished) return
        _state.update { it.copy(captured = it.captured - s.step.canonical, justCaptured = false, notice = null) }
        beginStep()
    }

    private fun finish() {
        stepJob?.cancel()
        val captured = _state.value.captured
        _state.update { it.copy(finished = true, saved = null) }
        listenJob?.cancel()  // closes our connection, so the daemon un-mutes the controller
        val e = entry ?: return
        if (captured.isEmpty()) {
            _state.update { it.copy(saved = false) }
            return
        }
        viewModelScope.launch {
            val pairs = captured.entries.associate { (canonical, raw) -> raw to canonical }
            try {
                DaemonClient.setMapping(e, kind, pairs)
                _state.update { it.copy(saved = true) }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                _state.update { it.copy(error = "Couldn't save the buttons: ${ex.message}") }
            }
        }
    }

    /** Forgets this controller's saved buttons. */
    fun clearSaved(entry: PriorityEntry) {
        viewModelScope.launch { runCatching { DaemonClient.setMapping(entry, "", emptyMap()) } }
    }

    /** Stops listening and un-mutes the controller. */
    fun cancel() {
        stepJob?.cancel()
        listenJob?.cancel()
        entry = null
    }

    override fun onCleared() {
        cancel()
    }
}
