package com.couchmode.app.daemon

import java.io.File
import java.io.RandomAccessFile

/** One device file, and whether this app's own process may open it. */
data class AccessResult(val path: String, val label: String, val canOpen: Boolean, val detail: String)

/**
 * Answers "could CouchMode run without root or adb, as an ordinary background (foreground-service) app?"
 * by trying to open, from the app's real process, the device files the daemon needs: /dev/uinput
 * (to create the virtual gamepad) and every /dev/input/event* (to read controllers). Nothing is
 * written or changed; files are opened and closed again.
 */
object AccessProbe {
    fun run(): List<AccessResult> {
        val results = mutableListOf<AccessResult>()
        results += tryOpen("/dev/uinput", "Virtual gamepad creation (uinput)", write = true)
        val names = deviceNames()
        File("/dev/input").listFiles { f -> f.name.startsWith("event") }
            ?.sortedBy { it.name.removePrefix("event").toIntOrNull() ?: 0 }
            ?.forEach { f -> results += tryOpen(f.path, names[f.name] ?: "input device", write = false) }
        return results
    }

    private fun tryOpen(path: String, label: String, write: Boolean): AccessResult = try {
        RandomAccessFile(path, if (write) "rw" else "r").close()
        AccessResult(path, label, true, "opened")
    } catch (e: Exception) {
        AccessResult(path, label, false, e.message?.substringAfterLast('(')?.trimEnd(')') ?: e.javaClass.simpleName)
    }

    /** eventN -> device name, from /proc/bus/input/devices (readable by everyone). */
    private fun deviceNames(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var name = ""
        runCatching {
            File("/proc/bus/input/devices").forEachLine { line ->
                when {
                    line.startsWith("N: Name=") -> name = line.substringAfter('"').substringBeforeLast('"')
                    line.startsWith("H: Handlers=") -> {
                        Regex("event\\d+").find(line)?.let { out[it.value] = name }
                    }
                }
            }
        }
        return out
    }
}
