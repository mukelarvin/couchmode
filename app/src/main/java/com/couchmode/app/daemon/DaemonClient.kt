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

data class PadDevice(val name: String, val id: String, val isSource: Boolean)

data class DaemonStatus(
    val source: String,
    val sourceConnected: Boolean,
    val virtualName: String,
    val sourceId: String,
)

/** One row of the daemon's priority list, highest priority first. */
data class PriorityEntry(val name: String, val id: String, val connected: Boolean, val active: Boolean)

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
        )
    }

    suspend fun listDevices(): List<PadDevice> =
        request("LIST", terminator = "END").mapNotNull { line ->
            val f = line.split('\t')
            if (f.size == 4 && f[0] == "D") PadDevice(f[1], f[2], f[3] == "1") else null
        }

    /** Devices are identified by name plus id: a pad and the vendor's virtual copy can share a name. */
    suspend fun priority(): List<PriorityEntry> =
        request("GETPRIO", terminator = "END").mapNotNull { line ->
            val f = line.split('\t')
            if (f.size == 5 && f[0] == "P") PriorityEntry(f[1], f[2], f[3] == "1", f[4] == "1") else null
        }

    /** Replaces the daemon's priority list (highest first). The daemon saves it and switches sources as needed. */
    suspend fun setPriority(entries: List<PriorityEntry>) {
        val command = "PRIORITY" + entries.joinToString("") { "\t${it.name}\t${it.id}" }
        check(request(command).firstOrNull() == "OK") { "daemon rejected PRIORITY" }
    }

    suspend fun setSource(device: PadDevice) {
        check(request("SOURCE ${device.name}\t${device.id}").firstOrNull() == "OK") { "daemon rejected SOURCE" }
    }

    /**
     * Streams raw key/axis events from the device until the collector is
     * cancelled. Uses its own connection; closing it stops the sniff daemon-side.
     */
    fun sniff(device: PadDevice): Flow<RawEvent> = callbackFlow {
        val socket = connect(timeoutMs = 0)
        socket.outputStream.write("SNIFF ${device.name}\t${device.id}\n".toByteArray())
        val reader = BufferedReader(InputStreamReader(socket.inputStream))
        val worker = thread(name = "couchmode-sniff") {
            try {
                val first = reader.readLine()
                if (first != "OK") {
                    close(IOException("daemon: $first"))
                    return@thread
                }
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
