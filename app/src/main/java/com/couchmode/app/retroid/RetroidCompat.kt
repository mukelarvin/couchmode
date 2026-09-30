package com.couchmode.app.retroid

import android.content.Context
import com.couchmode.app.daemon.DaemonClient

/** How CouchMode is currently keeping the Retroid input service away from its virtual gamepad. */
enum class CompatMode {
    /** The Retroid input service isn't installed (another device): nothing to do. */
    NOT_APPLICABLE,

    /** Our virtual gamepad (and the controllers in the list) are on the Retroid service's ignore list. */
    IGNORE_LIST,

    /** The ignore list couldn't be used, so the daemon creates a silent decoy gamepad instead. */
    DECOY,

    /** The user turned the workaround off. */
    OFF,
}

/** What [RetroidCompat.apply] ended up doing. [owned] = the ignore-list names CouchMode added and is responsible for. */
data class CompatResult(val mode: CompatMode, val owned: Set<String>)

/**
 * The Retroid input service ("RsMapping") copies and hides gamepads it finds, which can rename
 * or renumber CouchMode's virtual controller in emulators, and hands us a re-mapped copy of each
 * pad whose button codes differ from the real device's. See RetroidMapping.kt and PLAN.md
 * (2026-09-29 f/g, 2026-09-30 b/c/f). This brings the daemon and the Retroid service into the
 * state the user asked for: CouchMode's own gamepad and every controller in the list are on the
 * service's ignore list, so it never holds them and we always read the real devices. The decoy
 * is the fallback when the list can't be edited.
 */
object RetroidCompat {
    /** Must match the virtual device name the daemon creates. */
    const val VIRTUAL_NAME = "CouchMode Virtual Gamepad"

    /**
     * @param padNames names of the external controllers in the priority list (never the onboard controls).
     * @param owned names this app added to the ignore list earlier, so it may remove them again when they
     *   are no longer wanted. Names someone else put there are never touched.
     */
    suspend fun apply(context: Context, enabled: Boolean, padNames: Set<String>, owned: Set<String>): CompatResult {
        if (!RetroidMapping.isInstalled(context)) {
            runCatching { DaemonClient.setDecoy(false) }
            return CompatResult(CompatMode.NOT_APPLICABLE, emptySet())
        }
        if (!enabled) {
            (owned + VIRTUAL_NAME).forEach { RetroidMapping.setIgnored(context, it, false) }
            runCatching { DaemonClient.setDecoy(false) }
            return CompatResult(CompatMode.OFF, emptySet())
        }

        val ignored = RetroidMapping.ignoredDevices(context)
        if (ignored != null) {
            val wanted = padNames + VIRTUAL_NAME
            val nowOwned = owned.toMutableSet()
            var recreateVirtual = false
            for (name in wanted) {
                if (name in ignored) continue
                if (RetroidMapping.setIgnored(context, name, true)) {
                    if (name == VIRTUAL_NAME) recreateVirtual = true else nowOwned += name
                }
            }
            // Give back names we added that aren't in the list any more.
            for (name in owned - wanted) {
                if (name in ignored) RetroidMapping.setIgnored(context, name, false)
                nowOwned -= name
            }
            val after = RetroidMapping.ignoredDevices(context)
            if (after != null && VIRTUAL_NAME in after) {
                // The service only consults its list when a device appears, so a device it is already
                // holding has to be re-created before the new entry takes effect. (For a controller,
                // that means reconnecting it.)
                if (recreateVirtual) runCatching { DaemonClient.recreateVirtual() }
                runCatching { DaemonClient.setDecoy(false) }
                return CompatResult(CompatMode.IGNORE_LIST, nowOwned.filter { it in after }.toSet())
            }
        }
        // Couldn't reach or edit the ignore list (e.g. a firmware update changed it).
        runCatching { DaemonClient.setDecoy(true) }
        return CompatResult(CompatMode.DECOY, owned)
    }
}
