# CouchMode — build plan

Companion to `spec.md`. This file tracks phase status and a session log so
work can resume cleanly across separate Claude Code sessions. **Update the
session log at the end of every session**, even a partial one — note exactly
what's done and what the very next action is.

## Phases

- [x] **Phase 1 — Skeleton.** Gradle project structure, package
      `com.couchmode.app`, empty Compose `MainActivity`. *(Scaffolded; still
      not opened in Android Studio or built — see session log.)*
- [ ] **Phase 2 — Shizuku wiring (ON HOLD — root-daemon path being pursued instead, see 2026-09-29 log).** AIDL contract (`IInputService`), remote
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
- (Mostly moot now — see 2026-09-29 log.) Whether Shizuku has sufficient raw-device permissions on this firmware
  (`adb shell ls -l /dev/uinput /dev/input/event*` — not yet run).
- Static vs. connection-driven priority reordering in the UI.

## Session log

### 2026-09-29 (c) � CI green, daemon wired into Gradle (Claude Code)

- Generated the Gradle wrapper (9.7.1) and marked `gradlew` executable
  (`git update-index --chmod=+x`; Windows doesn't record it, CI failed with
  exit 126 until then). `ci.yml` now passes (assembleDebug + lint). Bumped
  checkout/setup-java to v5 in both workflows.
- Repo is public: https://github.com/mukelarvin/couchmode. `.gitattributes`
  keeps .sh/.c/CMakeLists/gradlew as LF. Git author for this repo is
  `Mukelarvin <mukelarvin@gmail.com>` (matches the GitHub account).
- `app/build.gradle.kts`: `externalNativeBuild` -> `daemon/CMakeLists.txt`,
  NDK 30.0.16248370, CMake 4.1.2, abiFilters arm64-v8a, `useLegacyPackaging`.
  The daemon target is named `libgamepad_merger.so` so AGP packages it in
  `lib/arm64-v8a/`; legacy packaging makes the installer extract it to the
  app's `nativeLibraryDir`. Confirmed present in the debug APK.
- **Verified on device:** `adb install` extracts `libgamepad_merger.so` to the app's `nativeLibraryDir` (`.../lib/arm64/`, mode 755), and running it from there as the shell user finds event7 and creates the virtual device. Not yet tried via the root menu.
- Then: multi-source priority, control channel, Phase 4 UI.

### 2026-09-29 (b) — gamepad_merger first cut written, NOT compiled (Claude Code)

- Ran the no-root check over adb: as the `shell` user, `/dev/uinput` **opens
  OK** and `getevent -lp` reads event7's capabilities (shell is in group
  `input`). So the daemon can probably run from adb shell without root —
  root may only be needed for boot-time launch. Still to confirm by running
  the daemon (incl. `EVIOCGRAB` as shell).
- event7 actually reports BTN_GAMEPAD(=SOUTH)/EAST/C/NORTH/WEST/Z, TL/TR,
  TL2/TR2, SELECT/START/MODE, THUMBL/R, BTN_DPAD_*, plus KEY_HOME/BACK/
  VOLUME*/F24/APPSELECT; axes X,Y,Z,RZ,GAS,BRAKE,HAT0X/Y. It also has both
  digital TL2/TR2 *and* analog GAS/BRAKE.
- Added `daemon/gamepad_merger.c` + `daemon/CMakeLists.txt`: finds source by
  name, mirrors its key/abs capabilities into a uinput device
  ("CouchMode Virtual Gamepad", 1209:C0DE), grabs the source, forwards 1:1,
  rescans every 1s if the source disappears (virtual device persists).
- **Verified on device (after Luke installed NDK 30.0.16248370 + CMake
  4.1.2):** built with Ninja for arm64-v8a, pushed to `/data/local/tmp`, run
  **as the adb `shell` user, no root**. Virtual device appeared with the same
  capabilities as event7 and real button presses showed up on it via
  `getevent -l`. `EVIOCGRAB` reported no error. Not yet confirmed: that
  event7 goes silent while grabbed (test was inconclusive), and that an
  emulator/Android sees the device.
- Dolphin lists "CouchMode Virtual Gamepad" and it works (Luke, on device).
- Latency: `gamepad_merger -t` logs, every 10s, the time from the source
  event's kernel timestamp (SYN_REPORT) to finishing the uinput write.
  First run (idle handheld, sticks + buttons): p50 22us, p90 ~45us, p99
  63-158us, max 541us. Covers only OUR hop; Android's downstream stack is
  identical with or without us. Not yet tested under game load (CPU
  contention could raise the tail; SCHED_FIFO is the fix if so).
- Build command (Git Bash; use `MSYS_NO_PATHCONV=1` with adb paths):
  `cmake -S daemon -B build/daemon -G Ninja -DCMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release`
- Next: confirm grab + that RetroArch/Android sees "CouchMode Virtual Gamepad";
  Gradle `externalNativeBuild` wiring; multi-source priority; control channel.

### 2026-09-29 — Root probe succeeded; root-daemon path is viable (Claude + Luke, via adb)

Findings from `tools/root-probe.sh` (v8), run through the device's
"Run script as Root" menu, output read with
`adb shell cat /data/local/tmp/couchmode-v8.txt`:

- Script runs as uid 0, SELinux context `u:r:pservice:s0`, `getenforce` =
  **Permissive**.
- `/dev/uinput` exists (`crw-rw-rw-`, uhid:uhid) and **opens for writing as
  root**. Background processes started from the script survive after it
  exits (earlier "alive loop" test).
- Onboard controller enumerates as **"Retroid Pocket Controller"**, bus 0003,
  **vendor 2022 / product 3001**. It is a *virtual* device (sysfs under
  `/devices/virtual/input`), i.e. created in userspace by the vendor's
  service, on `event7` this boot (event7 is `660 root:input`; the other
  nodes are `666`). Event numbers can change across boots — match by
  name + vendor/product, never by path. There is also a
  "Retroid Pocket Virtual Mouse" (2022:3001, version 00c8).
- Axes, decoded by hand from the ABS bitmask (double-check with
  `getevent -lp`): X, Y, Z, RZ (sticks), GAS/BRAKE (analog triggers),
  HAT0X/HAT0Y (d-pad).
- Storage: internal `emulated` plus an SD card (`5021-0000`). Android
  overlays their Download folders, which confused adb/MTP views.

Probe-tooling gotchas: files root writes under `/sdcard` were unreliable to
see from adb/MTP; a file root writes to `/data/local/tmp` with mode 644 is
readable from `adb shell`. Probe versions v1-v7 produced empty or no output;
suspected cause is the menu running a stale same-named copy (SD-card
overlay) — push each version under a brand-new filename.

Decision: pursue the root-launched native daemon (Option A). Shizuku wiring
(Phase 2 code) is on hold, not deleted.

**Next actions:**

1. Quick check (no root): `adb shell getenforce` and
   `adb shell "true 3>/dev/uinput && echo OK"`. If the shell can open
   uinput, root may matter less than assumed.
2. Write the native `gamepad_merger` daemon (Phase 5): open event7 by
   name/ID, create the virtual uinput gamepad with the same axis/button set,
   forward events 1:1; consider `EVIOCGRAB` on event7 so apps only see our
   virtual device, not both.
3. Decide the app-to-daemon control channel (priority list, per-controller
   bindings). Avoid `/sdcard`; `/data/local/tmp` config or a local socket.
4. Open concern: the built-in "classic vs Xbox" controller-style toggle
   probably acts inside the vendor service that creates event7, i.e.
   *before* our raw read, so wizard bindings could go stale if it is flipped
   after configuring. Test later: `adb shell getevent -l /dev/input/event7`,
   press one face button, flip the toggle, press it again, compare.

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
