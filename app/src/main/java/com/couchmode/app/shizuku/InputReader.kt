package com.couchmode.app.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.couchmode.app.BuildConfig
import com.couchmode.app.ipc.IInputService
import rikka.shizuku.Shizuku

/**
 * App-side manager for the Shizuku connection: pairing state, permission
 * request, and binding [InputService]. Modeled on RedTrigger's InputReader
 * (see spec.md, "Reference implementations").
 *
 * NOTE: this file is unverified against a real Shizuku install — the Gradle
 * project can't be built/run from where this was written (see PLAN.md
 * session log). Test carefully once Shizuku is actually paired on-device;
 * the Shizuku API surface used here (listener names, UserServiceArgs
 * builder) is correct as of Shizuku API 13.1.5 per current docs, but hasn't
 * been compiled against the real dependency yet.
 */
object InputReader {

    // Arbitrary but stable request code for Shizuku's permission callback.
    private const val PERMISSION_REQUEST_CODE = 0xC04C

    private var service: IInputService? = null
    private var onStateChanged: ((ShizukuState) -> Unit)? = null

    fun setListener(listener: ((ShizukuState) -> Unit)?) {
        onStateChanged = listener
    }

    fun isShizukuAvailable(): Boolean =
        try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }

    fun hasPermission(): Boolean =
        isShizukuAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    /** Call from a UI action. Requests permission if needed, then binds. */
    fun requestPermission() {
        if (!isShizukuAvailable()) {
            onStateChanged?.invoke(ShizukuState.Unavailable)
            return
        }
        if (hasPermission()) {
            bind()
        } else {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                bind()
            } else {
                onStateChanged?.invoke(ShizukuState.PermissionDenied)
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        onStateChanged?.invoke(ShizukuState.Available)
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        service = null
        onStateChanged?.invoke(ShizukuState.Unavailable)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val bound = IInputService.Stub.asInterface(binder)
            service = bound
            onStateChanged?.invoke(ShizukuState.Connected(bound.ping()))
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            onStateChanged?.invoke(ShizukuState.Unavailable)
        }
    }

    /** Call once, early (e.g. Application.onCreate). */
    fun registerLifecycleListeners() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    fun unregisterLifecycleListeners() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
    }

    private fun bind() {
        val args = Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, InputService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("input")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

        Shizuku.bindUserService(args, serviceConnection)
    }

    fun unbind() {
        try {
            service?.destroy()
        } catch (_: Throwable) {
            // Remote process may already be gone — fine.
        }
        service = null
    }
}
