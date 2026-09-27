# CouchMode — build plan

Companion to `spec.md`. This file tracks phase status and a session log so
work can resume cleanly across separate Claude Code sessions. **Update the
session log at the end of every session**, even a partial one — note exactly
what's done and what the very next action is.

## Phases

- [x] **Phase 1 — Skeleton.** Gradle project structure, package
      `com.couchmode.app`, empty Compose `MainActivity`. *(Scaffolded; still
      not opened in Android Studio or built — see session log.)*
- [ ] **Phase 2 — Shizuku wiring.** AIDL contract (`IInputService`), remote
      `InputService`, app-side `InputReader` connection manager, and a
      Phase-2-only status screen (replaces the placeholder text) that
      requests permission, binds, and shows the remote process's pid.
      *(Code written; genuinely untested — nothing has been built or run
      yet. See session log.)*
- [ ] **Phase 3 — Device enumeration.** Dynamic `/dev/input/eventX` scanning
      by name + `EVIOCGID` (not fixed paths). Surface via
      `InputManager.InputDeviceListener` to the UI layer.
- [ ] **Phase 4 — Priority list UI.** Compose screen: drag-to-reorder list,
      add-controller picker, backed by persisted `PriorityManager` state.
      Buildable/testable independent of the merge daemon working yet.
- [ ] **Phase 5 — `gamepad_merger` native core.** `uinput` virtual device
      creation, full capability declaration, raw event forwarding from a
      single hardcoded source first (prove the plumbing) before adding
      multi-source priority logic.
- [ ] **Phase 6 — Wizard.** Capability probe (`EVIOCGBIT`), positional
      prompts, controller-diagram screen, writes captured bindings into
      each controller's config.
- [ ] **Phase 7 — Trigger analog/digital handling.** Layered onto the
      wizard once general bindings work.
- [ ] **Phase 8 — Boot persistence + release packaging.** `BootReceiver`
      still not started. Release packaging/CI *(GitHub Actions build-sign-
      release workflow, Obtainium-ready releases)* is scaffolded ahead of
      schedule — see 2026-09-27 (b) below — but untested end-to-end (no
      keystore generated yet, no tag pushed yet).

See `spec.md` for the full design rationale behind each phase — this file is
just status + resume points, not the design doc.

## Open questions (from spec.md, still unresolved)

- Exact boot-persistence mechanism on this specific Retroid firmware.
- Whether Shizuku has sufficient raw-device permissions on this firmware
  (`adb shell ls -l /dev/uinput /dev/input/event*` — not yet run).
- Static vs. connection-driven priority reordering in the UI.

## Session log

### 2026-09-27 (c) — Phase 2 (Shizuku wiring) scaffolded (Claude, via chat + local filesystem access)

Repo still not `git init`'d as of this entry — user is installing Android
Studio and will commit manually. **Nothing in this repo has been built,
synced, or run yet.** Everything below is written against documented Shizuku
API behavior (confirmed via fresh docs lookup this session, not just
training-data recall) but is unverified in practice.

Added:

- `app/src/main/aidl/com/couchmode/app/ipc/IInputService.aidl` — `ping()`
  (returns the remote process's pid, proves it's a real separate process)
  and the required `destroy()` at Shizuku's fixed transaction code
  `16777114` — don't renumber that.
- `app/src/main/java/com/couchmode/app/shizuku/InputService.kt` — the
  remote `IInputService.Stub` implementation. No-arg constructor (required
  — Shizuku instantiates it reflectively).
- `app/src/main/java/com/couchmode/app/shizuku/InputReader.kt` — app-side
  connection manager: availability check, permission request/listener,
  `bindUserService`/`ServiceConnection`, exposed as a `ShizukuState` the UI
  observes. Modeled on RedTrigger's `InputReader` per spec.md's component
  table.
- `app/src/main/java/com/couchmode/app/shizuku/ShizukuState.kt` — sealed
  state: `Unavailable` / `Available` / `PermissionDenied` / `Connected(pid)`.
- `app/src/main/java/com/couchmode/app/CouchModeApplication.kt` — registers
  Shizuku's lifecycle listeners once, app-wide (not per-Activity).
- `AndroidManifest.xml` — added `android:name=".CouchModeApplication"` and
  the required `ShizukuProvider` `<provider>` boilerplate.
- `app/build.gradle.kts` — added `dev.rikka.shizuku:api:13.1.5` and
  `:provider:13.1.5` (confirmed current on Maven Central this session), and
  `buildFeatures.buildConfig = true` (AGP 9 doesn't generate `BuildConfig`
  by default anymore, and `InputReader` needs
  `BuildConfig.APPLICATION_ID`/`VERSION_CODE`/`DEBUG`).
- `MainActivity.kt` — replaced the Phase 1 placeholder text with a real (if
  temporary) screen: shows `ShizukuState` as text, "Connect via Shizuku"
  button. This screen itself gets replaced in Phase 4.

**Not done yet, next action for whoever picks this up:**

1. Everything from the Phase 1 session-log entry below still applies first
   (open in Android Studio, resolve any version-upgrade prompts, confirm a
   build — now with Shizuku deps resolving too — actually compiles).
2. Install Shizuku itself on the Retroid (from shizuku.rikka.app or its
   GitHub releases) and pair it (wireless debugging, since we're not
   assuming Magisk/root for this path — root is the separate path in
   spec.md).
3. Run the app, tap "Connect via Shizuku", grant the permission prompt,
   confirm the screen shows a remote pid — that's Phase 2's actual
   success condition, and the first real signal on whether this firmware's
   Shizuku grants raw-device access at all (still doesn't answer the
   `/dev/uinput` permission question specifically — that's Phase 3's
   `adb shell ls -l` check, still not run).
4. If step 3 fails, the likely suspects in order: Shizuku not paired/
   running, permission not actually granted, or a version mismatch between
   the `dev.rikka.shizuku` artifacts pinned here and whatever Shizuku app
   version ends up installed — check Shizuku's GitHub releases if so.
5. Once confirmed working, move to **Phase 3** — dynamic evdev enumeration
   inside `InputService`, surfaced through `InputManager.InputDeviceListener`
   on the app side.

### 2026-09-27 (b) — CI/release workflow scaffolded (Claude, via chat + local filesystem access)

Jumped ahead to part of Phase 8 (release packaging) while Phase 1 is still
unverified, since it's independent, low-risk plumbing work:

- `.github/workflows/ci.yml` — builds `assembleDebug` + `lint` on every push
  to `main` and every PR.
- `.github/workflows/release.yml` — triggers only on a `v*` tag push,
  decodes a keystore from a GitHub secret, runs `assembleRelease`, attaches
  the signed APK to an auto-created GitHub Release (`softprops/action-gh-release`).
- `app/build.gradle.kts` — added a `signingConfigs["release"]` block that
  reads `RELEASE_KEYSTORE_PATH`/`_PASSWORD`, `RELEASE_KEY_ALIAS`,
  `RELEASE_KEY_PASSWORD` from environment variables. Unset locally (so local
  `assembleRelease` is just unsigned), set from secrets in CI.
- `RELEASING.md` — step-by-step: generate the keystore locally with
  `keytool`, back it up somewhere durable, base64-encode it, register the
  four required GitHub secrets, then how to cut a release (bump
  `versionCode`/`versionName`, tag `vX.Y.Z`, push the tag).

**Not done yet, next action for whoever picks this up:**

1. Nobody has generated the actual release keystore yet — do that first
   (`RELEASING.md` step 1), back it up somewhere durable immediately, and
   register the four GitHub secrets before ever pushing a tag.
2. `ci.yml`/`release.yml` haven't been run even once — first real push to
   GitHub will be the first test of `ci.yml`; don't push a `v*` tag until
   Phase 1's build is actually confirmed working locally first (see the
   2026-09-27 (a) entry below) — a signed release of a placeholder screen
   isn't worth cutting yet, but the pipeline is ready for when it is.
3. Once confirmed, resume Phase 1's remaining steps (launcher icon, git
   init) or move to Phase 2.

### 2026-09-27 (a) — Phase 1 scaffolded (Claude, via chat + local filesystem access)

Created via direct filesystem write (not yet verified building — no Android
SDK available in that session's environment):

- `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`
- `app/build.gradle.kts` (Kotlin 2.3.21, Compose BOM 2026.06.00, AGP 9.0.0,
  minSdk 26 / target+compileSdk 36 — treat these as a starting point, not
  pinned; let Android Studio's sync/upgrade prompts move them forward)
- `app/src/main/AndroidManifest.xml` — single launcher `MainActivity`, no
  icon yet, comments marking where `MergeService`/`BootReceiver` land later
- `app/src/main/java/com/couchmode/app/MainActivity.kt` — placeholder
  Compose screen (just a text label), real screens are Phase 4
- `app/src/main/java/com/couchmode/app/ui/Theme.kt` — default M3 color
  schemes, no brand palette yet
- `app/src/main/res/values/{strings,themes}.xml`
- `.gitignore`, `README.md`

**Not done yet, next action for whoever picks this up:**

1. Open the folder in Android Studio. It will very likely prompt to
   upgrade AGP/Gradle/SDK versions and to generate the Gradle wrapper
   (`gradlew`/`gradlew.bat`/`gradle-wrapper.properties`) — accept those,
   they weren't generated here since it needs a real Gradle install.
2. Confirm it builds and installs on a device/emulator (even showing just
   the placeholder text is success for Phase 1).
3. Add a real launcher icon via Android Studio's Image Asset wizard, wire
   `android:icon` into the manifest.
4. Decide whether to commit + push to GitHub now (repo not yet
   initialized as a git repo as of this session — `git init` first) before
   or after step 1–3.
5. Once Phase 1 is confirmed building, move to **Phase 2** — clone
   RedTrigger locally for reference and start the Shizuku/AIDL fork per
   `spec.md`'s component table.
