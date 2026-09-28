// Top-level build file. AGP 9+ has built-in Kotlin support — the separate
// org.jetbrains.kotlin.android plugin is no longer needed (and is actively
// incompatible with AGP 9's new DSL). See
// https://kotl.in/gradle/agp-built-in-kotlin. The Compose compiler plugin
// below should track whatever Kotlin version AGP's built-in Kotlin is
// actually using (2.2.10 per AGP 9's docs as of this write) — if Android
// Studio reports a version mismatch, let its Upgrade Assistant resolve it
// rather than hand-editing this further; that's a faster feedback loop than
// guessing from outside a real Gradle environment.
plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
