package com.couchmode.app.retroid

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Client for the Retroid input service ("RsMapping", package com.rp.mapping).
 *
 * That service takes over one external gamepad at a time, hiding the original
 * and publishing its own copy (see PLAN.md, 2026-09-29 f/g). It skips devices
 * whose name is on its own "don't hold" list, and exports a service to edit
 * that list. There is no published SDK: the interface below was read from the
 * app's compiled code (AIDL descriptor and transaction numbers), so it can
 * change with a firmware update. Every call here must fail soft.
 */
object RetroidMapping {
    const val PACKAGE = "com.rp.mapping"
    private const val ACTION = "com.ro.mapping.action.MAPPING_SERVICE"
    private const val DESCRIPTOR = "com.ro.mapping.sdk.IServerApi"
    private const val TX_GET_IGNORED = 37  // M(): List<String>
    private const val TX_SET_IGNORED = 38  // N(int op, String name): op 1 = add, else remove
    private const val BIND_TIMEOUT_MS = 3000L

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** Names of devices the Retroid service will not take over, or null if it can't be reached. */
    suspend fun ignoredDevices(context: Context): List<String>? = withService(context) { binder ->
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            binder.transact(TX_GET_IGNORED, data, reply, 0)
            reply.readException()
            reply.createStringArrayList() ?: emptyList()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Adds or removes [name] on the ignore list. Returns false if the service couldn't be reached. */
    suspend fun setIgnored(context: Context, name: String, ignored: Boolean): Boolean =
        withService(context) { binder ->
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeInt(if (ignored) 1 else 0)
                data.writeString(name)
                binder.transact(TX_SET_IGNORED, data, reply, 0)
                reply.readException()
                true
            } finally {
                data.recycle()
                reply.recycle()
            }
        } ?: false

    /** Binds the service, runs [block] with its binder, unbinds. Null on any failure. */
    private suspend fun <T> withService(context: Context, block: (IBinder) -> T): T? {
        val app = context.applicationContext
        var connection: ServiceConnection? = null
        try {
            val binder = withTimeoutOrNull(BIND_TIMEOUT_MS) {
                suspendCancellableCoroutine<IBinder?> { cont ->
                    val c = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, service: IBinder) {
                            if (cont.isActive) cont.resume(service)
                        }

                        override fun onServiceDisconnected(name: ComponentName) {}
                        override fun onNullBinding(name: ComponentName) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    connection = c
                    val intent = Intent(ACTION).setPackage(PACKAGE)
                    val started = try {
                        app.bindService(intent, c, Context.BIND_AUTO_CREATE)
                    } catch (e: SecurityException) {
                        false
                    }
                    if (!started) cont.resume(null)
                }
            } ?: return null
            return try {
                block(binder)
            } catch (e: Exception) {
                null  // RemoteException, SecurityException, a changed interface, ...
            }
        } finally {
            connection?.let { runCatching { app.unbindService(it) } }
        }
    }
}
