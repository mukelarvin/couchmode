package com.couchmode.app.daemon

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import kotlin.concurrent.thread

/**
 * A controller the daemon can see. [id] is bus:vendor:product:version; [uniq] is the device's unique
 * string (the Bluetooth address for a BT pad, empty for built-in or virtual devices). Name + id +
 * uniq together tell apart two identical pads, and a pad from the Retroid service's copy of it.
 */
data class PadDevice(val name: String, val id: String, val isSource: Boolean, val uniq: String = "")

data class DaemonStatus(
    val source: String,
    val sourceConnected: Boolean,
    val virtualName: String,
    val sourceId: String,
    val decoyActive: Boolean,
)

/** One row of the daemon's priority list, highest priority first. */
data class PriorityEntry(
    val name: String,
    val id: String,
    val uniq: String,
    val connected: Boolean,
    val active: Boolean,
    /** The saved button map: 0 none, 1 saved and valid, 2 saved but learned through the other path (copy vs real). */
    val mapping: Int = 0,
)

data class RawEvent(val type: Int, val code: Int, val value: Int)

/**
 * Talks to gamepad_merger over its abstract unix socket "@couchmode". See the
 * protocol summary at the top of daemon/gamepad_merger.c. The socket works the
 * same whether the daemon was started as root or as the adb shell user.
 */
object DaemonClient {
    private const val SOCKET_NAME = "couchmode"
    private const val EV_KEY = 1
    private const val EV_ABS = 3

    private fun connect(timeoutMs: Int = 2000): LocalSocket {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = timeoutMs
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        return socket
    }

    /** Sends one command on a short-lived connection and collects reply lines up to [terminator] (or the first line). */
    private suspend fun request(command: String, terminator: String? = null): List<String> =
        withContext(Dispatchers.IO) {
            connect().use { socket ->
                socket.outputStream.write("$command\n".toByteArray())
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                val lines = mutableListOf<String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line == terminator) break
                    lines += line
                    if (terminator == null) break
                }
                lines
            }
        }

    /** True if the daemon answers. Never throws. */
    suspend fun isRunning(): Boolean =
        try { request("PING").firstOrNull() == "PONG" } catch (e: IOException) { false }

    suspend fun status(): DaemonStatus {
        val f = request("STATUS").first().split('\t')
        return DaemonStatus(
            source = f[1],
            sourceConnected = f[2] == "1",
            virtualName = f[3],
            sourceId = f.getOrElse(4) { "" },
            decoyActive = f.getOrElse(5) { "0" } == "1",
        )
    }

    suspend fun listDevices(): List<PadDevice> =
        request("LIST", terminator = "END").mapNotNull { line ->
            val f = line.split('\t')
            if (f.size >= 4 && f[0] == "D") PadDevice(f[1], f[2], f[3] == "1", f.getOrElse(4) { "" }) else null
        }

    /** Devices are identified by name plus id: a pad and the vendor's virtual copy can share a name. */
    suspend fun priority(): List<PriorityEntry> =
        request("GETPRIO", terminator = "END").mapNotNull { line ->
            val f = line.split('\t')
            if (f.size >= 6 && f[0] == "P") {
                PriorityEntry(f[1], f[2], f[3], f[4] == "1", f[5] == "1", f.getOrElse(6) { "0" }.toIntOrNull() ?: 0)
            } else null
        }

    /** Replaces the daemon's priority list (highest first). The daemon saves it and switches sources as needed. */
    suspend fun setPriority(entries: List<PriorityEntry>) {
        val command = "PRIORITY" + entries.joinToString("") { "\t${it.name}\t${it.id}\t${it.uniq}" }
        check(request(command).firstOrNull() == "OK") { "daemon rejected PRIORITY" }
    }

    /** Turns the decoy gamepad (see daemon/gamepad_merger.c, create_decoy) on or off; the daemon remembers it. */
    suspend fun setDecoy(on: Boolean) {
        check(request("DECOY ${if (on) 1 else 0}").firstOrNull() == "OK") { "daemon rejected DECOY" }
    }

    /** Makes the daemon destroy and re-create its virtual gamepad (a one-time renumbering for apps). */
    suspend fun recreateVirtual() {
        check(request("RECREATE").firstOrNull() == "OK") { "daemon rejected RECREATE" }
    }

    /**
     * Saves a controller's button map: raw evdev key code -> canonical code. [kind] is "raw" or "copy"
     * (what [sniff] reported while learning); the daemon only applies the map while the source is
     * reached the same way. Empty [pairs] clears the map.
     */
    suspend fun setMapping(entry: PriorityEntry, kind: String, pairs: Map<Int, Int>) {
        val text = pairs.entries.joinToString(",") { "${it.key}=${it.value}" }
        val command = "SETMAP\t${entry.name}\t${entry.id}\t${entry.uniq}\t$kind\t$text"
        check(request(command).firstOrNull() == "OK") { "daemon rejected SETMAP" }
    }

    suspend fun setSource(device: PadDevice) {
        check(request("SOURCE ${device.name}\t${device.id}\t${device.uniq}").firstOrNull() == "OK") { "daemon rejected SOURCE" }
    }

    /**
     * Streams raw key/axis events from the device until the collector is
     * cancelled. Uses its own connection; closing it stops the sniff daemon-side.
     * With [mute], the daemon stops forwarding that device to the virtual gamepad for as long as
     * the stream is open (used by the button wizard). [onKind] gets "raw" or "copy": whether the
     * events come from the real device or the Retroid service's copy of it.
     */
    fun sniff(device: PadDevice, mute: Boolean = false, onKind: (String) -> Unit = {}): Flow<RawEvent> = callbackFlow {
        val socket = connect(timeoutMs = 0)
        if (mute) socket.outputStream.write("MUTE 1\n".toByteArray())
        socket.outputStream.write("SNIFF ${device.name}\t${device.id}\t${device.uniq}\n".toByteArray())
        val reader = BufferedReader(InputStreamReader(socket.inputStream))
        val worker = thread(name = "couchmode-sniff") {
            try {
                if (mute && reader.readLine() != "OK") {
                    close(IOException("daemon refused MUTE"))
                    return@thread
                }
                val first = reader.readLine()
                if (first == null || !first.startsWith("OK")) {
                    close(IOException("daemon: $first"))
                    return@thread
                }
                onKind(first.substringAfter('\t', "raw"))
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line == "GONE") break
                    val f = line.split(' ')
                    if (f.size == 4 && f[0] == "E") {
                        val type = f[1].toInt()
                        if (type == EV_KEY || type == EV_ABS) trySend(RawEvent(type, f[2].toInt(), f[3].toInt()))
                    }
                }
                close()
            } catch (e: IOException) {
                close()  // socket closed by awaitClose, or daemon went away
            }
        }
        awaitClose {
            try { socket.close() } catch (_: IOException) {}
            worker.interrupt()
        }
    }
}
