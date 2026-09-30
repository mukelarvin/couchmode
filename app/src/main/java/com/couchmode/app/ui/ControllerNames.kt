package com.couchmode.app.ui

/** Friendly names for well-known devices; anything else shows its real name. */
fun displayName(name: String): String = when (name) {
    "Retroid Pocket Controller" -> "Onboard controls"
    else -> name
}

/** "bus:vendor:product:version" -> "vendor:product", as shown in the UI. */
fun shortId(id: String): String = id.split(':').let { if (it.size == 4) "${it[1]}:${it[2]}" else id }

/**
 * The Retroid input service (RsMapping) publishes a re-mapped copy of an
 * external pad under the pad's own name with ID 2022:3001 and a non-zero
 * version. Only the onboard controls use version 0000. Copies are noise in a
 * picker; the real pad is listed separately. See PLAN.md (2026-09-29 f/g).
 */
fun isVendorCopy(id: String): Boolean = id.startsWith("0003:2022:3001:") && !id.endsWith(":0000")
