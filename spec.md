# CouchMode — technical spec

## Problem

Android gaming handhelds (this project targets a Retroid Pocket) expose onboard
controls as one input device and, when docked to a TV, a Bluetooth/2.4GHz-dongle/
wired controller as a separate device. Emulator frontends (RetroArch, Dolphin,
GameNative) bind Player 1 per physical device, so docking currently means
reconfiguring inputs for every emulator every time.

CouchMode's job: present one stable virtual controller to Android regardless of
which physical source is actually active, so every emulator/app is configured
once, ever, against that virtual device.

## Non-goals

- Rewriting each emulator's own config/autoconfig files at the app layer. Rejected:
  requires per-emulator format knowledge and won't generalize to things like
  GameNative (PC emulation), which has no autoconfig concept to hook into.
- On-screen/software virtual gamepad (Android's built-in VirtualGamepad demo
  pattern) — not applicable, this is about merging real physical devices.

## Core architecture

A native daemon operating at the evdev/uinput level, below Android's per-app
input focus and below any individual emulator's config format:

1. Enumerate physical controller input nodes (`/dev/input/eventX`)
2. Watch for connect/disconnect of each configured controller
3. Create one persistent virtual gamepad via Linux `uinput`
   (fixed name/vendor/product ID, full button+axis capability set declared
   via `UI_SET_KEYBIT`/`UI_SET_ABSBIT`)
4. Forward raw events from whichever configured controller is highest-priority
   *and currently connected* into that virtual device
5. Every downstream app (RetroArch, Dolphin, GameNative, anything) binds once,
   to the virtual device, and never needs reconfiguring again

### Privilege model

Raw evdev/uinput access requires elevated privilege on Android. Two paths:

- **Root (primary path).** The Retroid firmware has a built-in "Run script as
  Root" one-shot elevated-execution tool (manual trigger, not a boot hook by
  itself). Use it once to install the daemon and wire up boot persistence —
  exact persistence mechanism (init.d-equivalent vs. an app + `libsu` on
  `BOOT_COMPLETED`) still needs testing on this specific firmware.
- **Shizuku (possible non-root alternative).** Gives an app shell-UID
  privileges via one-time ADB pairing, no system root needed. Whether shell
  UID has rw access to `/dev/uinput` and the raw event nodes on Retroid's
  firmware specifically is unconfirmed — verify with
  `adb shell ls -l /dev/uinput /dev/input/event*` before committing to this
  path.

### Reference implementations (do not build from scratch)

- **[RedTrigger](https://github.com/zampierilucas/RedTrigger)** — open-source
  Android app doing exactly the Shizuku + native uinput plumbing we need
  (for a different problem: exposing hidden shoulder triggers on a Red Magic
  phone). Use its structure as the template:

  | RedTrigger component | CouchMode equivalent |
  |---|---|
  | `TriggerManager` | `PriorityManager` — holds ordered device-priority list (persisted) |
  | `TriggerService` | `MergeService` — foreground service, owns Shizuku session lifecycle |
  | `InputReader` | same role — manages the Shizuku UserService connection |
  | `InputService` (fixed SAR0/SAR1 nodes) | `InputService`, but enumerates `/dev/input/eventX` **dynamically** via `/proc/bus/input/devices` or `EVIOCGNAME`/`EVIOCGID` ioctls — not hardcoded paths, since our device set is arbitrary and hot-plugged |
  | `uinput_injector` (remaps a couple of keys) | `gamepad_merger` — declares full gamepad capability set, forwards raw events 1:1 from whichever device is active |
  | `BootReceiver` | same |
  | `DebugLog` | same |
  | *(none)* | **New:** Compose screen — live `InputDevice` list with drag-and-drop priority reorder |

- **[MoltenGamepad](https://github.com/jgeumlek/MoltenGamepad)** — Linux
  daemon whose entire purpose is this same merge/priority logic (multi-device
  watch, single virtual output, priority rules), built against standard
  evdev/uinput. Not packaged for Android, but its merge-logic design is the
  reference to adapt rather than reinvent.

### Device identity (stability across reconnects)

Match physical controllers by **name + vendor/product ID** (via `EVIOCGID`),
not by `/dev/input/eventX` path — path/number is not stable across
reconnects (a Bluetooth pad can re-enumerate as `event9` instead of `event7`).

## Button/axis mapping model

Canonical identity is **positional**, using standard Linux evdev codes, not
letter labels:

- `BTN_SOUTH` / `BTN_EAST` / `BTN_NORTH` / `BTN_WEST` (note: `BTN_A`/`BTN_B`/
  `BTN_X`/`BTN_Y` are literally `#define` aliases for these same codes in the
  kernel — position is already the canonical identity at the OS level)
- `BTN_TL` / `BTN_TR` (bumpers), `BTN_TL2` / `BTN_TR2` (triggers)
- `BTN_THUMBL` / `BTN_THUMBR` (stick clicks)
- `BTN_START` / `BTN_SELECT`
- `ABS_HAT0X` / `ABS_HAT0Y` (d-pad)

Per-controller config stores **raw evdev code → canonical position**, learned
by the wizard capturing whichever physical button the user actually presses —
not by assuming a label. This makes the mapping immune to:

- Xbox-vs-Nintendo ABXY position swaps
- Vendor-level relabeling tools (e.g. Retroid's own built-in "classic vs
  Xbox" layout toggle) — confirmed to not require special-casing, since
  whatever layer that toggle operates at (cosmetic launcher-only, RetroArch
  config rewrite, or Android `.kl` key-layout remap), our daemon reads raw
  evdev beneath all three, and the wizard's physical-press binding survives
  it either way. Only a kernel/HID-level remap of the physical wire signal
  itself (uncommon for a settings toggle) would require attention, and
  that's a non-issue per the user Retroid tool doesn't conflict.
- Cheap/generic HID pads that report raw ordinal buttons with no positional
  semantics at all (the actual case the wizard exists to rescue)

The virtual merged device always emits the canonical codes; interpreting
"south" as A (Xbox-style) or B (Switch-style) is left to each downstream app,
which already expects to do that.

## Analog vs. digital trigger handling

- **Detection:** during the L2/R2 wizard step, whichever event type arrives
  first decides it — `EV_ABS` (`ABS_Z`/`ABS_RZ`) → analog, `EV_KEY`
  (`BTN_TL2`/`BTN_TR2`) → digital-only. Falls out of the wizard naturally,
  no separate detection pass.
- **Synthesis:** the virtual device always declares `ABS_Z`/`ABS_RZ`. Digital
  sources synthesize press → axis max, release → axis 0. **No ramping** —
  instant jump, kept simple by design.
- **Manual override:** inline toggle on the wizard's trigger step (not a
  separate settings screen), defaulting to the auto-detected value. Exists
  mainly for pads that report a nominally-analog-but-jittery `ABS_Z`
  alongside a `BTN_TL2` — forcing digital avoids trigger noise.

## Wizard UX

- RetroArch-style: one button/axis at a time, "press the highlighted button"
- **Capability probe first** (`EVIOCGBIT`) — only prompt for buttons/axes the
  device actually reports, instead of a fixed template
- **Positional diagram, not letters** — generic controller silhouette with an
  arrow pointing at the position being requested (south/east/west/north,
  bumpers, triggers, stick clicks, d-pad, start/select), same sequence
  RetroArch uses for its own bind-all flow
- **Analog stick capture:** "push fully in each direction," not a single
  static frame, to correctly distinguish a real `ABS_X`/`ABS_Y` axis from a
  d-pad misreporting as a hat switch
- **Timeout-to-skip:** ~8–10s auto-skip per prompt, so a controller missing a
  button (e.g. no L3/R3 on some fightsticks) doesn't block the wizard
- Skip / retry controls per step; step counter + progress dots

## App UI

1. **Priority list (home screen).** Drag-to-reorder list of added controllers,
   drag handle + rank badge per row, name + connection status dot
   (connected/not), tap a row to re-run its wizard, "+ Add controller" at
   the bottom. Topmost = highest priority.
2. **Add controller.** List of currently-detected `InputDevice`s not yet
   added (name + vendor:product), already-added devices shown grayed out
   with a checkmark so they can't be double-added.
3. **Button configuration wizard.** See above.

Open UX question (not yet decided): should priority reordering happen
automatically based on live connection state, or should the user's drag
order be static, with the merge daemon separately just walking the list
top-to-bottom for the first *currently connected* entry?

## Naming & distribution

- **App name: CouchMode**
- **Distribution:** GitHub repo + [Obtainium](https://github.com/ImranR98/Obtainium)
  (tracks GitHub Releases), **no Play Store**
- Implications:
  - Obtainium diffs on GitHub **Releases** (tag + attached APK), not commits
    on the default branch — CI must build and attach a signed APK on tag push
  - **Signing key must stay constant** across all releases — Obtainium (like
    Android) refuses an in-place update if the signing key changes. Generate
    the release keystore once, store it durably (repo secret + backup), never
    regenerate it.
  - **`versionCode` must strictly increase** per release; `versionName` is
    just the human-readable string. A `justfile` with bump-patch/bump-minor
    commands (as RedTrigger uses) is a reasonable pattern to copy.
  - **GitHub Actions:** workflow on tag push → `./gradlew assembleRelease` →
    sign with the keystore secret → attach APK to the tag's GitHub Release
  - README needs only a repo link — that's the entire "install instructions"
    for anyone adding it via Obtainium

## Open questions / not yet resolved

- Exact boot-persistence mechanism on this specific Retroid firmware
  (init.d-equivalent vs. app+`libsu` on `BOOT_COMPLETED`) — needs testing on
  the actual device.
- Whether Shizuku has sufficient raw-device permissions on this firmware —
  needs `adb shell ls -l /dev/uinput /dev/input/event*` to confirm before
  treating Shizuku as a real alternative to root.
- Static vs. connection-driven priority reordering (see Wizard/UI section
  above).

## Target hardware / primary use case

Retroid Pocket Android handheld. Primary scenario: dock the handheld to a TV
(video-out) and switch to a Bluetooth, 2.4GHz-dongle, or wired controller for
a console-style big-screen experience, without reconfiguring RetroArch,
Dolphin, or GameNative each time.