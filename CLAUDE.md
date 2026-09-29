# CouchMode — context for Claude Code

Handoff from a claude.ai chat session (Sept 29, 2026). Read this first, then
`spec.md` (design) and `PLAN.md` (phase status + dated session log). Update the
PLAN.md session log at the end of every session.

## What this is

An Android app + root daemon that gives a gaming handheld ONE stable virtual
gamepad, so docking to a TV with a Bluetooth / 2.4GHz / wired controller
doesn't mean re-binding Player 1 in every emulator (RetroArch, Dolphin,
GameNative). Distributed via GitHub Releases + Obtainium. No Play Store.
App name: **CouchMode**, package `com.couchmode.app`.

Owner: Luke. Working style: wants simple, direct answers; got frustrated by
the Shizuku detour, so avoid over-engineering and avoid piling up speculative
theories. Say plainly when something is unverified. Decisions he has made: no
trigger ramping (digital trigger -> instant 0/100%), positional (not lettered)
button wizard, drag-to-reorder priority list, topmost controller wins.

## Architecture (decided): root daemon, NOT Shizuku

- A native C daemon (`gamepad_merger`) runs as root: reads the configured
  physical controllers' `/dev/input/eventX`, forwards events from the
  highest-priority *connected* one into a single persistent `uinput` virtual
  gamepad. Emulators bind once to that virtual device.
- Java/Kotlin cannot do the uinput ioctls, so the daemon needs NDK/CMake
  (Gradle `externalNativeBuild`). Not set up yet.
- Started via the handheld's built-in **Settings -> "Run script as Root"**
  (one tap per boot; no boot hook found yet). Background processes started
  from that script survive after it exits (verified).
- Shizuku Phase 2 code (`app/src/main/java/com/couchmode/app/shizuku/`, the AIDL
  file, `ShizukuProvider` in the manifest, the two Shizuku deps) is ON HOLD.
  Not deleted. Remove it when the root path works. It did compile and install.
- The Android app itself stays unprivileged: UI for the priority list, add
  controller, and the button-config wizard (see spec.md, "App UI").

## Device facts (Retroid Pocket, SoC "kalama" / SD 8 Gen 2, Android 13)

Verified from `tools/root-probe.sh` v8 output:

- Root script context: uid 0, `u:r:pservice:s0`, `getenforce` = **Permissive**.
- `/dev/uinput` is `crw-rw-rw-` (uhid:uhid); opening it for write **works**.
  There is no `/dev/input/uinput`.
- Onboard controls = **"Retroid Pocket Controller"**, vendor 2022 product 3001
  version 0000, on `event7` that boot, sysfs `/devices/virtual/input/input7`
  (a virtual device created by Retroid's own service). `event7` is mode 660
  root:input; the other nodes are 666.
- **"Retroid Pocket Virtual Mouse"** (`event8`) has the SAME 2022:3001
  (version 00c8). Match devices by NAME (+ version), never VID:PID alone, and
  never by event number (it changes between boots).
- Reported axes: ABS_X, ABS_Y, ABS_Z, ABS_RZ, ABS_GAS, ABS_BRAKE,
  ABS_HAT0X/Y. Z/RZ are the right stick; **analog triggers are GAS/BRAKE**.
  (This corrects spec.md, which said ABS_Z/ABS_RZ for triggers.) Verify with
  `getevent -lp` before relying on it.
- Storage: internal `/storage/emulated` plus an SD card at `/storage/5021-0000`;
  Android overlays their Download folders.
- adb shell is the unprivileged `shell` user: no `su`, cannot list `/data`.
  Unverified: whether shell can open uinput / read event7 (shell is normally in
  the `input` group). Cheap test: `adb shell "true 3>/dev/uinput && echo OK"`.

## How to run things as root on this device (hard-won)

- adb is not on PATH: `C:\Users\lukem\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- Scripts run through Settings -> "Run script as Root" (file picker). Because of
  the internal/SD overlay, a same-named old copy may run instead of your new
  one. **Push every version under a brand-new filename**:
  `adb push tools\root-probe.sh /sdcard/Download/<unique-name>.sh`
- Script format that works (modeled on armada's `flash_abl.sh` template):
  `#!/bin/sh`, then plain sequential commands. No braces/blocks or variables
  carried across lines; keep it boring. `tools/root-probe.sh` (v8) is the
  known-good example.
- Getting output back: write results under `/data/local/tmp`, `chmod 644`,
  then `adb shell cat /data/local/tmp/<file>`. Files root writes under
  `/sdcard` were often invisible to adb/MTP. Output via `log -t` to logcat
  showed nothing (cause unknown, probably a stale script copy) — don't rely
  on it.
- Detached start that is known to survive: 
  `setsid nohup sh -c '...' >/dev/null 2>&1 &`

## Design notes for the daemon (from spec.md + findings)

- Canonical identity is positional evdev codes (BTN_SOUTH/EAST/NORTH/WEST,
  BTN_TL/TR, BTN_TL2/TR2, BTN_THUMBL/R, BTN_START/SELECT, ABS_HAT0X/Y). Per
  controller store raw code -> canonical position, learned by the wizard.
- Simplest first cut: read the capability set of event7 with EVIOCGBIT at
  startup and create the virtual device with the SAME axes/buttons, so it
  behaves like the onboard pad. Use `EVIOCGRAB` on the source so apps only see
  our virtual device, not both. Give the virtual device its own stable name.
- Digital-only trigger source (BTN_TL2/TR2) -> synthesize ABS_BRAKE/ABS_GAS
  0/max instantly. Auto-detect analog vs digital in the wizard, with an
  override toggle.
- **Open concern:** Retroid's built-in "classic vs Xbox" controller-style
  toggle probably acts inside the vendor service that creates event7, i.e.
  BEFORE our raw read, so wizard bindings for the onboard pad could go stale if
  the toggle is flipped afterwards. Test: `adb shell getevent -l
  /dev/input/event7`, press a face button, flip the toggle, press again.
- App <-> daemon control channel is undecided. Constraints: an unprivileged app
  cannot write `/data/local/tmp`; avoid `/sdcard`. Idea (not decided): the app
  writes config into its own private files dir, which root can read; status
  goes back via a local socket. Also unsolved: how the launch script finds the
  binary (idea: copy it from the app's native lib dir to `/data/local/tmp`,
  chmod, start detached).

## Repo / build state

- Gradle project: AGP 9.4.0 (built-in Kotlin: do NOT apply
  `org.jetbrains.kotlin.android`), Compose compiler plugin `2.2.10`,
  Compose BOM `2026.06.00`, compileSdk/targetSdk 36 (compileSdk must stay
  high even though the device runs Android 13), minSdk 26, `buildConfig` and
  `aidl` enabled. It builds and installs; the Phase 2 screen showed
  "Connect via Shizuku".
- No `gradlew`/`gradlew.bat`/`gradle-wrapper.jar` at the repo root yet, so
  `.github/workflows/ci.yml` and `release.yml` (which call `./gradlew`) will fail
  until the wrapper is generated and committed.
- Release signing keystore has NOT been generated; see `RELEASING.md`. Do not
  push a `v*` tag before then. Never commit `*.keystore` / `*.jks`.
- `.gitignore` was rewritten late; run `git status` and untrack anything that
  slipped into the first commit (`.gradle/`, `.idea/`, `.kotlin/`,
  `local.properties`, `build/`).
- Launcher icon not added yet.

## Suggested next steps

1. Generate the Gradle wrapper; make `ci.yml` pass.
2. Set up NDK/CMake and write `gamepad_merger` (C): find event7 by name, create
   the virtual gamepad mirroring its capabilities, forward events 1:1, grab the
   source. Prove it by running it via the root menu and confirming an emulator
   sees the new device.
3. Then multi-source priority (Bluetooth pad appears/disappears), then the
   app-to-daemon channel, then Phase 4 UI, then the wizard.
