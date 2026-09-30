package com.couchmode.app.ui

import com.couchmode.app.daemon.PadDevice
import com.couchmode.app.daemon.PriorityEntry

/** Friendly names for well-known devices; anything else shows its real name. */
fun displayName(name: String): String = when (name) {
    "Retroid Pocket Controller" -> "Onboard controls"
    else -> name
}

/** True for the Retroid's built-in controls (its button layout is already the canonical one). */
fun isOnboard(name: String): Boolean = name == "Retroid Pocket Controller"

/** "bus:vendor:product:version" -> "vendor:product", as shown in the UI. */
fun shortId(id: String): String = id.split(':').let { if (it.size == 4) "${it[1]}:${it[2]}" else id }

/**
 * The Retroid input service (RsMapping) publishes a re-mapped copy of an
 * external pad under the pad's own name with ID 2022:3001 and a non-zero
 * version. Only the onboard controls use version 0000. Copies are noise in a
 * picker; the real pad is listed separately. See PLAN.md (2026-09-29 f/g).
 */
fun isVendorCopy(id: String): Boolean = id.startsWith("0003:2022:3001:") && !id.endsWith(":0000")

/** The tail of a unique string such as a Bluetooth address ("E4:17:D8:78:71:73" -> "71:73"); "" if none. */
fun shortUniq(uniq: String): String = if (uniq.length >= 5) uniq.takeLast(5) else uniq

/**
 * Key for a controller's user-chosen name. Name + id + uniq, so two identical pads can have different
 * names (for example "Purple Pro controller" and "Black Pro controller").
 */
fun nameKey(name: String, id: String, uniq: String) = "$name|$id|$uniq"

fun PriorityEntry.nameKey() = nameKey(name, id, uniq)

fun PadDevice.nameKey() = nameKey(name, id, uniq)

/** What to show for a controller: the user's own name if they gave one, else a friendly default. */
fun labelFor(realName: String, key: String, userNames: Map<String, String>): String =
    userNames[key]?.takeIf { it.isNotBlank() } ?: displayName(realName)
