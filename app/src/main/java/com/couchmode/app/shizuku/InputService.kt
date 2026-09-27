package com.couchmode.app.shizuku

import android.os.Process
import com.couchmode.app.ipc.IInputService
import kotlin.system.exitProcess

/**
 * Runs in a separate process under shell (or root) identity, started by
 * Shizuku via [rikka.shizuku.Shizuku.bindUserService]. Must have a no-arg
 * constructor — Shizuku instantiates this reflectively, not through normal
 * Android component creation.
 *
 * Phase 2 scope only: prove this process actually spins up and answers a
 * Binder call from the app process. Phase 3 adds real evdev device
 * enumeration here (see spec.md's InputService row in the RedTrigger
 * component-mapping table, and PLAN.md Phase 3).
 */
class InputService : IInputService.Stub() {

    override fun ping(): Int = Process.myPid()

    override fun destroy() {
        exitProcess(0)
    }
}
