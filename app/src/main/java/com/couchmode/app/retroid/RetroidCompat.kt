package com.couchmode.app.retroid

import android.content.Context
import com.couchmode.app.daemon.DaemonClient

/** How CouchMode is currently keeping the Retroid input service away from its virtual gamepad. */
enum class CompatMode {
    /** The Retroid input service isn't installed (another device): nothing to do. */
    NOT_APPLICABLE,

    /** Our virtual gamepad is on the Retroid service's ignore list. */
    IGNORE_LIST,

    /** The ignore list couldn't be used, so the daemon creates a silent decoy gamepad instead. */
    DECOY,

    /** The user turned the workaround off. */
    OFF,
}

/**
 * The Retroid input service ("RsMapping") copies and hides gamepads it finds, which can
 * rename or renumber CouchMode's virtual controller in emulators. See RetroidMapping.kt
 * and PLAN.md (2026-09-29 f/g, 2026-09-30 b). This brings the daemon and the Retroid
 * service into the state the user asked for, preferring the ignore list and falling
 * back to the decoy.
 */
object RetroidCompat {
    /** Must match the virtual device name the daemon creates. */
    const val VIRTUAL_NAME = "CouchMode Virtual Gamepad"

    suspend fun apply(context: Context, enabled: Boolean): CompatMode {
        if (!RetroidMapping.isInstalled(context)) {
            runCatching { DaemonClient.setDecoy(false) }
            return CompatMode.NOT_APPLICABLE
        }
        if (!enabled) {
            RetroidMapping.setIgnored(context, VIRTUAL_NAME, false)
            runCatching { DaemonClient.setDecoy(false) }
            return CompatMode.OFF
        }

        val ignored = RetroidMapping.ignoredDevices(context)
        if (ignored != null) {
            var ok = true
            if (VIRTUAL_NAME !in ignored) {
                ok = RetroidMapping.setIgnored(context, VIRTUAL_NAME, true) &&
                    RetroidMapping.ignoredDevices(context)?.contains(VIRTUAL_NAME) == true
                // The service only consults its list when a device appears, so a device it is
                // already holding has to be re-created before the new entry takes effect.
                if (ok) runCatching { DaemonClient.recreateVirtual() }
            }
            if (ok) {
                runCatching { DaemonClient.setDecoy(false) }
                return CompatMode.IGNORE_LIST
            }
        }
        // Couldn't reach or edit the ignore list (e.g. a firmware update changed it).
        runCatching { DaemonClient.setDecoy(true) }
        return CompatMode.DECOY
    }
}
