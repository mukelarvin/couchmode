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

### 2026-09-30 (h) - starting the daemon at boot: undecided (Claude Code)

Luke rebooted as a test: the daemon does NOT start by itself (Phase 8 not built). Everything
it saves survives (priority list, maps, decoy option, app prefs). The Retroid ignore list is kept
in the Retroid app's own saved preferences and is name-based only (no Bluetooth/ID), so one
"Nintendo Switch Pro Controller" entry covers every pad with that name; it should survive reboots
(not yet proven; the app re-applies it on launch anyway).

- Checked the firmware's init configuration: no standard user-script boot hook (no userinit,
  init.d, service.d). The "Run script as Root" menu sends commands to a Retroid root service; the
  settings app also has an "app auto launch" list (launches apps at boot, no root).
- Two further steps (asking that root service from the app as an ordinary app, and reading its
  binary) were blocked by the tool's safety check. NOT pursued or worked around. Luke decides.
  Trade-off to tell him: if an ordinary app could call it, that would be a firmware security hole
  that Retroid could close.
- Options given to Luke: (1) one tap per boot via Run script as Root, made smoother in the app;
  (2) ask Retroid for a supported run-at-startup option; (3) Luke explicitly approves a harmless
  test of whether an app may call the service. Recommendation: 1 now, 2 in parallel.
- **Can it run without root? (measured inside the real app process, Developer tools ->
  "Check what this app may open"):** yes for everything except the onboard controller.
  /dev/uinput and every other input node (external pads, the Retroid copies, the virtual mouse,
  buttons, touch, haptics) open fine as an ordinary app; `/dev/input/event7` ("Retroid Pocket
  Controller", mode 660 root:input) is DENIED. The daemon has always run as the adb shell user
  (which is in group `input`), never as root, so root is not required, but something with `input`
  group access must read the onboard pad. An earlier `run-as` test was not representative (it
  inherited the shell's groups). Luke also noted OdinTools needed background permission: any
  always-on app here needs battery-optimization exemption / allow-in-background.
- Options offered (Luke to choose): root start per boot (now); an unprivileged foreground service
  for external pads + root tap to add the onboard pad ("two tiers"); ask Retroid; Shizuku-style
  wireless-debugging start.

- Root start script: `tools/couchmode-start.sh`; pushed as `/sdcard/Download/couchmode-start-0930b.sh`
  (first time it is used through the root menu; `pm` under the root context is still untested).
- USB pads: Luke wants to test one. Identity may need `phys` (USB port) when `uniq` is empty.

### 2026-09-30 (g) - ignore the controllers in the list; D-pad setup (Claude Code)

- **Retroid ignore list now covers the controllers in the list** (not only our virtual gamepad),
  so the Retroid service should stop holding/copying them and we always read the real devices
  (one setup per pad). The app owns only the names it added (kept in prefs
  `retroid_owned_ignores`), never the onboard controls, and re-applies whenever the set of
  external controller names changes. It takes effect when a pad next connects, so a pad that is
  currently held needs reconnecting. The service still holds pad E9:92:0B until it is reconnected.
  Luke approved this. A leftover friendly name from my wizard test ("TestWiz") was removed from
  the app prefs.
- **Trigger question (Luke):** measured with him squeezing the triggers: the Switch Pro pad sends
  only digital ZL/ZR (values 0/1) through the Retroid copy, and the real device has no trigger
  axes at all; CouchMode outputs BTN_TL2/TR2 plus an on/off ABS_BRAKE/GAS. Retroid's trigger
  settings are unchanged since the start of the session (trigger_input_mode=2).
- **D-pad in the wizard** (Luke asked about pads whose D-pad is buttons, or held sideways, like a
  Wiimote): 4 new steps (up/down/left/right, before the bumpers; 17 steps now). A direction can be
  a key press or a hat axis moving one way (source code `10000 + axis*2 + positive`); the map's
  targets are pseudo codes 1000..1003. The daemon keeps D-pad state and emits HAT0X/HAT0Y on the
  virtual gamepad (always declared now), dropping the consumed source events. "Finish now" ends
  the wizard early; the diagram highlights individual D-pad arms; test mode handles hats.
  Verified with fake pads under exclusive-grab safety (tools/test): buttons -> hat, and a
  sideways hat -> rotated hat, exactly as expected. The wizard's D-pad steps and the new
  diagram were NOT looked at on screen.
- **Still not configurable:** which physical axes are the sticks and analog triggers
  (RX/RY -> Z/RZ and Z/RZ -> BRAKE/GAS are automatic for Linux-layout pads). A controller with
  unusual axes (or held sideways with a stick) would need axis steps ("push fully up").
- New `tools/test/` (vgrab, uitest4, dpad_test.sh, README) implements the CLAUDE.md safety rules.

### 2026-09-30 (f) - first real wizard run: X/Y convention bug, maps per connection (Claude Code)

Luke calibrated pad E9:92:0B and reported West and East swapped in Dolphin. What the data showed
(read-only: saved map, Dolphin's GCPadNew.ini, daemon log):

- **My bug: wrong canonical codes for west/north.** The wizard used the Linux names (west = 308,
  north = 307). Android, Dolphin (which is bound to `Button A/B/X/Y`) and the Retroid's own pad use
  the Xbox convention: KEYCODE_BUTTON_X (code 307) is the LEFT face button and BUTTON_Y (308) the
  TOP one. Fixed: WEST = 307, NORTH = 308 (comment in WizardViewModel.kt says not to "fix" it
  back). The map file now has an `@layout 2` header; maps from the first wizard (no header /
  layout 1) are ignored with a log line, so those pads must be set up again.
- **A pad is reached two ways, and they send different codes.** Raw device, or the Retroid
  service's copy (when the service holds the pad). Which one we get changes across reconnects and
  daemon restarts (E9:92:0B was raw when calibrated, then held/copy after a restart, so the
  raw-learned map rightly stopped applying). Maps are now stored PER KIND: an entry can have a raw
  map and a copy map and the one matching the current connection is applied. GETPRIO map field:
  1 = set up for the current connection, 2 = only for the other one (row says "Set up buttons
  again"), 0 none. The status for the attached source uses how it is really being read.
- **Saved capture looked rotated:** the old map had raw 307 for the "right" prompt, 304 for "left",
  308 for "top" (physical top, right, left on a label-based Nintendo pad, i.e. presses out of order
  relative to the prompts). Cause not determined (user error vs something in the wizard). Added:
  the result screen lists the raw button captured for each prompt, and a "Test buttons" mode lights
  up the diagram position for each press (controller stays muted). Not yet seen running with a real pad.
- **Open question for Luke (bypass):** add each listed pad's name to the Retroid ignore list too, so
  the service never holds/copies them and we always get the raw device (one setup per pad,
  consistent codes). Takes effect when the pad next connects. Not done; needs Luke's OK.
- Not verified: anything with the real pads' buttons (needs Luke; fake input must follow the
  testing rules in CLAUDE.md).

### 2026-09-30 (e) - button wizard and friendly names (Claude Code)

Luke wanted the wizard so the A/B (and X/Y) buttons land in the right places, and friendly
per-controller names ("Purple Pro controller"). Both built; the wizard is positional, not
lettered (a diagram highlights where to press), because "A" is in a different place on Xbox and
Nintendo pads.

- **Daemon: per-controller button maps.** raw evdev key code -> canonical code, saved in
  `/data/local/tmp/couchmode-maps.conf` and keyed by the controller entry (name, id, uniq).
  Commands: `SETMAP<TAB>name<TAB>id<TAB>uniq<TAB>kind<TAB>from=to,...` (empty pairs clear),
  `GETMAP`, `MUTE 0|1`; GETPRIO gets a 7th field (0 none, 1 saved, 2 saved but learned the other
  way); SNIFF replies `OK<TAB>raw|copy`. While a map is active, mapped codes are translated and
  gamepad-range buttons (BTN_SOUTH..BTN_THUMBR) the map does not cover are dropped; other keys
  pass through. Trigger synthesis now runs on the translated code.
- **kind = raw|copy.** A map learned on the Retroid service's copy of a pad is wrong for the real
  device and vice versa (the copy's layout is already remapped by the vendor). The map records
  which one it was learned on and is only applied while the source is reached the same way; the
  list row shows "Set up buttons again" when it doesn't match.
- **MUTE:** the wizard's connection mutes the active source so its presses don't reach the
  virtual gamepad (otherwise the east button would act as Back inside the app). Released when
  the connection closes.
- **App:** `wizard/WizardViewModel.kt` (13 steps: 4 face buttons, bumpers, digital triggers,
  select, start, home, stick clicks; 10s auto-skip; Skip / Retry / Back; ignores a press already
  used; ignores input in the first 0.4s of a step), `ui/WizardScreen.kt` (controller diagram that
  highlights the position, progress dots, result screen). Tap a controller row to open it.
  Analog triggers: skip those two steps (their axes already pass through).
- **Friendly names:** pencil button on each row; stored in the app's preferences, keyed by
  name|id|uniq so two identical pads can be named differently; blank restores the default.
  Used in the list, picker and wizard title.
- **Verified on the device with fake pads (no presses):** maps translate, uncovered buttons are
  dropped, a wrong-kind map is refused, MUTE silences the virtual gamepad, maps survive a daemon
  restart; the wizard run end to end against a fake pad with a scrambled layout produced exactly
  the expected 13 pairs (and stayed on screen although a press was the east button = Back).
  Rename dialog and persistence checked through the UI.
- **NOT verified:** with the real 8BitDo pads (needs Luke). Particularly: whether setting up the
  pad fixes A/B in Dolphin, and which kind (raw or copy) each real pad is reached through.
- Not done: analog trigger axis mapping and stick axis choice in the wizard (RX/RY -> Z/RZ and
  Z/RZ -> triggers are automatic for Linux-layout pads), d-pad as buttons, "reset all" UI.

### 2026-09-30 (d) — two identical pads (Claude Code)

Luke connected two "Nintendo Switch Pro Controller"s and could only add one: the
first was saved as name-only, which matched both, and the Retroid service holds only
one pad (the other stays a raw node), so the held pad had only a uniq-less copy.

- Identity is now name + `bus:vendor:product:version` + **uniq** (the Bluetooth
  address for BT pads). Protocol: LIST `D name id isSource uniq`; GETPRIO `P name id
  uniq connected active`; PRIORITY / SOURCE / SNIFF take name, id, uniq; STATUS has a
  7th field (uniq). Config lines are `name<TAB>id<TAB>uniq` (old two-field lines still
  load: uniq empty = match any).
- The daemon reads `/proc/bus/input/devices`, which still lists a pad whose
  /dev/input node the Retroid service hid, including its uniq. A held pad is listed
  (and stored) under its own id/uniq; to forward it the daemon attaches the vendor's
  copy, but only if the wanted uniq really is the held pad (`find_held_raw`).
- Verified on the two real pads (71:73 raw node, 92:0B held): each selectable alone
  by uniq, both in one list honoured the order, saved correctly. In the app both
  are addable and rows show the last two bytes of the address. Button-level
  behaviour of the two pads was not exercised (needs presses).
- Also fixed: two Kotlin files had non-UTF-8 bytes from a Windows-default-encoding
  edit ("·" and "…" are now `·` / `…` escapes), and PLAN.md had 8 stray
  cp1252 bytes (em dashes) that showed as garbage on GitHub. When scripting edits on
  this machine always pass `encoding='utf-8'` to `open()` (Python's default here is cp1252).
- Follow-up bug (Luke caught it): list rows and "remove" identified an entry by name + id,
  which two identical pads share, so they dragged together and Remove would delete both.
  Identity is now `PriorityEntry.identity()` = name|id|uniq everywhere. Verified with
  `adb shell input swipe`: top down, bottom to top, middle down, middle up all give the
  expected order with the two identical pads.
- Caveat: entries saved before this (name only) still match any pad of that name.
  Friendly per-pad names (rename) are not built; rows show the address tail.

### 2026-09-30 (c) — Retroid ignore list replaces the decoy (Claude Code)

Luke wanted a smooth experience whether the controller or the daemon starts first,
and asked about disconnecting external pads at startup (not needed, and Bluetooth
off also drops audio; USB/dongles can't be disconnected anyway).

- **Verified on device (BT off for the test, then back on):** the app can bind the
  Retroid service (`com.rp.mapping`, needs `<queries>`), read its ignore list
  (tx 37) and edit it (tx 38). With our name "CouchMode Virtual Gamepad" on the list,
  a stand-in device of that name is NOT adopted: no copy, node stays visible.
  Without the entry it is copied and hidden (baseline reproduced in the same run).
  Adding the name while the device is already held does NOT release it; the device
  must be re-created.
- **Built:** daemon commands `DECOY 0|1` (create/destroy at runtime; saved as
  `@decoy<TAB>0|1` in the priority file; STATUS has a 6th field) and `RECREATE`
  (destroy + recreate the virtual device, re-attach the source). App:
  `retroid/RetroidMapping.kt` (binder client), `retroid/RetroidCompat.kt` (modes
  NOT_APPLICABLE / IGNORE_LIST / DECOY / OFF), applied once the daemon is reachable:
  Retroid service present -> ensure our name is ignored (recreate the virtual device
  only if we just added it) -> decoy off; if the list can't be read/edited -> decoy on
  as a fallback. A "Retroid compatibility" card with a switch and a plain-language
  status sits on the home screen (hidden on non-Retroid devices). Turning it off
  removes our entry and the decoy.
- **Verified end to end:** fresh daemon (decoy on) + name not on the list -> opening
  the app added the name, recreated the device and removed the decoy; saved as off;
  then the 8BitDo woke: the Retroid service held it (copy only), our device stayed
  visible, the picker listed the pad (via the copy), and it became the active source.
- Not verified: behaviour after a Retroid firmware update, a reboot with the root
  menu launch, and Dolphin binding stability across pad connect/disconnect (needs a
  human with the pad). Risks: the binder interface is reverse-engineered and
  obfuscated (classes `n0.a`), so it can change; fallback is the decoy.
- Dev tools still has Read list / Ignore ours / Stop ignoring buttons.

### 2026-09-30 (b) — RsMapping has a device blacklist we can probably use instead of the decoy

Read from the APK's dex (dexdump), not yet exercised on the device:
- `AppMapping.isShouldHold(name)` = NOT `RsDeviceManage.isInBlackList(name)`: the
  only Java-side test for "adopt this device" is a name blacklist stored in the
  RsMapping app's SharedPreferences (key `hold_devices_black_list`, JSON list of
  names). A blacklisted device is not held: no copy, node not hidden.
- RsMapping exports a bindable service: action `com.ro.mapping.action.MAPPING_SERVICE`,
  `com.ro.mapping.service.ApiService`, exported=true, no permission seen. AIDL
  descriptor `com.ro.mapping.sdk.IServerApi`. Transactions (code: method):
  37 `M()` -> List<String> (get blacklist), 38 `N(int op, String name)` (op 1 =
  add to blacklist, anything else = remove; returns void). Parcel: enforce
  interface, writeInt(op), writeString(name). Obfuscated names (n0.a), so a
  firmware update may renumber them.
- Plan: bind that service from the app and blacklist "CouchMode Virtual Gamepad"
  (and stop creating the decoy when it worked); keep the decoy as a fallback only
  if the call fails. Needs `<queries>` for package visibility. Changes a
  persistent setting inside Retroid's app (reversible with op 0); ask Luke
  before writing. Reading the list (37) is harmless and is the first test.
- Also: `ApiService` methods include config/trigger/mode getters and setters we
  have not read; the same service may let us read trigger mode etc.

### 2026-09-30 — multi-source priority (Claude Code)

- Daemon: priority list (up to 8 name + `bus:vendor:product:version` entries).
  Forwards from the highest-ranked connected one; while a lower entry is active
  it re-checks once a second and switches UP when a better pad appears; when the
  active one disappears it falls back down the list. All buttons/axes are
  released on every switch. The list persists in
  `/data/local/tmp/couchmode-priority.conf` (mode 666) and is reloaded at start
  (`-s` on the command line overrides it; default = "Retroid Pocket Controller").
  Protocol: `PRIORITY<TAB>name<TAB>id...`, `GETPRIO`, `SOURCE` (one-entry
  shorthand). Polling, not inotify, for hot-plug detection.
- Verified on device with throwaway uinput "controllers" (no button presses):
  fallback chain, switch up, switch down on removal, events forwarded, list
  reloaded after a daemon restart.
- App: `DaemonScreen` now shows the priority list (Up / Down / Remove, status
  "in use / connected / not connected", refreshed every 2s) and "Add to
  priority" on present controllers. Temporary developer UI; Phase 4 replaces it
  (drag to reorder, nicer add-controller picker).
- **Luke's request (TODO): make the decoy visible and optional.** The app
  should explain the decoy controller and have a switch for it, preset on first
  run by detecting Retroid's software (e.g. `com.rp.mapping` installed), and
  off for other devices or if Retroid changes the app. Needs a daemon command
  (e.g. `DECOY 0|1`, create/destroy at runtime) and a saved setting; the
  decoy is currently always created at daemon start. The flow must tolerate
  RsMapping already holding a real pad when the decoy is created.
- **App UI, first pass (Phase 4), from `docs/concept/app-screens.png`:**
  `ControllerListScreen` (priority list: drag handle, rank badge, status dot,
  "In use / Connected / Not connected", remove, "Add controller"),
  `AddControllerScreen` (present controllers not yet added, "Already added"
  section, hides Retroid's re-mapped copies via `isVendorCopy`), and the old
  developer screen behind the overflow menu ("Developer tools"). `MainViewModel`
  polls the daemon every 2s only while a screen is visible. Builds and lints;
  NOT yet seen running on the device (adb dropped). Friendly names: only
  "Retroid Pocket Controller" -> "Onboard controls" so far; no rename UI yet.
  Wizard (concept screen 3) not built. Open design question for Luke: the concept
  shows a lettered "A" prompt, the spec says positional prompts + diagram.
- Gap for raw (non-Retroid-copy) pads: the daemon forwards their codes as-is,
  but many Linux pads report the right stick as ABS_RX/RY while our virtual
  device (copied from the Retroid) uses ABS_Z/RZ, so the right stick and possibly
  face buttons won't line up until canonical mapping exists.

- **Axis remap (2026-09-30):** a source with RX/RY gets them forwarded as our
  Z/RZ (the Retroid-convention right stick), scaled to the virtual range; if it
  also has Z/RZ (Xbox/PlayStation-style analog triggers) those go to BRAKE/GAS,
  scaled. Verified with fake pads (no presses): RX/RY +-20000 -> Z/RZ +-20000;
  trigger 255/255 -> 32767, 128/255 -> 16447. Luke confirmed the raw Switch Pro
  pad's right stick did NOT work before this; needs re-testing with the real pad.
  Face-button layout (Nintendo A/B vs Xbox) is still NOT handled - wizard's job.
- **Pad held by RsMapping (2026-09-30):** if a pad is already connected when
  the daemon (and decoy) starts, RsMapping keeps holding it and its raw /dev/input
  node stays hidden, so an entry with the raw id showed "Not connected". The daemon's
  `open_by_name` now falls back to the vendor copy (2022:3001 non-zero version)
  of a pad with the same name, and prefers the real node when an entry has an empty
  id. The app's picker shows a copy when its real twin isn't available and stores
  such entries with an empty id. Verified on device: entry went to "In use".
  Note: via the copy the right stick is already in Z/RZ (vendor remap), so the
  RX/RY remap only matters when the real node is usable.
- **UI bugs fixed the same day:** rows stuck mid-drag (rows were not keyed by
  controller, so a swap tore down the drag gesture) and some reorders not saved
  (drag handler compared against a stale copy of the list). Verified on the device
  with `adb shell input swipe`: 4 drags -> 4 saves.

- Not done yet: canonical virtual layout + rumble, first-run flow, wizard,
  the face-button swap investigation (parked by Luke), root-menu launch test.

### 2026-09-29 (g) — decoy device to keep RsMapping off our gamepad (Claude Code)

Experiments (BT pad asleep, so no other external pad; throwaway uinput devices):
- With no other external pad, RsMapping adopts ANY gamepad-like device (tried
  bus virtual/USB/BT, various vendor IDs): creates the 2022:3001 v0064 copy
  under the same name and hides the original's /dev/input node.
- It is sticky: it keeps the device it adopted until that disappears, then hops
  to the next one. Native symbols `isShouldhold` / `setNowHoldDevice` fit.
- **Decoy works:** a silent gamepad-like device created first gets adopted, and a
  second device created afterwards keeps its node and is seen directly by Android.
  When the decoy was removed, the copy hopped onto the real device.
- Daemon now creates "CouchMode Decoy (ignore)" (1209:c0df, never emits events)
  before the real virtual gamepad and sleeps 1.5s so RsMapping adopts it;
  hidden from LIST. Verified with the real daemon (BT pad asleep): copy on the
  decoy, ours visible, `dumpsys input` lists both.
- Side effects: apps list one extra gamepad named "CouchMode Decoy (ignore)"
  (actually RsMapping's copy of it) that never does anything.
- **Not yet verified:** with the decoy held, does a BT pad that connects LATER
  stay un-copied (expected, since sticky) and remain usable as a source; what
  happens on reboot ordering (if RsMapping already holds a real pad when the
  daemon starts, it stays on that pad - fine, ours is still visible). Relies
  on undocumented firmware behaviour of a Retroid Pocket Nova (firmware may
  change it).
- Next: verify with the BT pad reconnecting; then canonical layout + rumble,
  multi-source priority; consider selecting the RAW BT node as a source.

### 2026-09-29 (f) — the Retroid service clones every gamepad, including ours (Claude Code)

Luke unplugged the BT pad (no fallback - expected, single-source only), replugged
it, Dolphin's "Android/N/CouchMode..." number changed 2 -> 3, and after
re-mapping Dolphin the A/B and X/Y pairs were swapped. Investigation:

- The daemon never restarted (one "created virtual device" in its log), so it
  did not renumber anything.
- `/proc/bus/input/devices` shows TWO "CouchMode Virtual Gamepad" devices: ours
  (bus 0006, 1209:c0de) and a copy with the Retroid's own ID (bus 0003,
  2022:3001, version 0064, sysfs /devices/virtual/input). The same happened
  earlier with "Nintendo Switch Pro Controller" (that was the copy, not the pad).
- `/dev/input/eventN` for OUR device does not exist (kernel lists the handler;
  the node is gone) while the copy's node exists. `dumpsys input` lists only
  the copy (with VIBRATOR class); our device is not in Android's EventHub.
  => **Android apps have only ever seen the vendor's copy of our device.**
  Hypothesis (not proven): a Retroid service creates a re-mapped virtual copy of
  each external gamepad, removes/hides the original node, and re-creates its own
  devices (even "Retroid Pocket Controller" went input7 -> input28) whenever a
  pad connects/disconnects.
- Dolphin's "Android/N/" is Android's per-device ControllerNumber (first free
  slot), so it changes whenever the vendor re-creates devices.
- The A/B, X/Y swap is probably the vendor's layout remapping (the classic vs
  Xbox toggle from CLAUDE.md) applied to the copy. Unverified: need to compare
  the physical button -> code seen on the source vs on the copy.
- Consequences: our "one stable device" is currently stable only up to the
  vendor's copy. Need to learn what triggers cloning (name? bus? sysfs path?
  class?) and whether our device can avoid it, e.g. different bus/vendor, or
  a name the service ignores. The daemon's LIST hides devices named like ours,
  so it can't select the copy of itself as a source (feedback loop guard).
- **CORRECTION / experiments (same day, no button presses needed - test
  devices were made with a throwaway uinput tool):**
  - Test gamepads with bus virtual/USB/BT and various vendor IDs (incl. Xbox
    045e:028e and Nintendo 057e:2009) were NOT copied or hidden. Device IDs
    are not the trigger.
  - There is exactly ONE Retroid copy (2022:3001 v0064) at a time. It mirrors a
    single external gamepad and is sticky: it kept mirroring the same device
    until that device disappeared, then re-created itself on the next one.
    Stopping the daemon moved the copy from "CouchMode Virtual Gamepad" to the
    real "Nintendo Switch Pro Controller"; restarting the daemon did NOT make it
    copy ours again.
  - Working theory: when the BT pad was unplugged, ours was the only external
    gamepad, so the service mirrored ours and hid its /dev/input node; apps then
    saw only the copy. With the BT pad connected first (now), the copy targets
    the pad and Android sees OUR device directly (`dumpsys input` path
    /dev/input/event12, stable for 60s+). So the "Android has only ever seen the
    copy" statement above was only true during that window.
  - Likely cause of the swapped A/B, X/Y: Luke re-mapped Dolphin while the copy
    (vendor layer in the middle) was the device it saw; now Dolphin sees our
    device directly. Still unverified.
  - Implication: which device apps see (ours vs a vendor copy of ours) depends
    on which external gamepads are connected. Dolphin's `Android/N/` slot also
    changes. Treat any binding made while a copy is in the path as suspect.
    Re-check with the BT pad off (our device may get copied again).

- (Superseded) experiment with the virtual device's bus/vendor/name to see if the
  service still clones it; sniff source vs copy for the button layout.

- **RsMapping investigation (read-only, from the APK pulled off the device;
  conclusions come from class/symbol names, not from running or decompiling
  it).** `/system/app/RsMapping` = `com.rp.mapping` v3.0, runs as uid system,
  native lib `librsinput.so`. It is Retroid's input engine:
  - It talks to the handheld's MCU (`MCUInit`, `sendDataToMCU`, `updateMCU`),
    and creates the virtual devices through uinput: gamepad
    (`NativeGamepadDevice`, with `nativeSetName`, sticks, GAS/BRAKE), mouse
    ("Retroid Pocket Virtual Mouse"), keyboard, touchscreen. Vendor 2022:3001
    comes from `get_gamepad_vendor/product`. So the onboard controls are NOT a
    kernel evdev device; "Retroid Pocket Controller" is RsMapping's output.
    => The onboard pad can't be bypassed; only external pads can.
  - Features: controller style (`persist.sys.gamepad.type` 0/1/2), analog vs
    digital trigger mode (`setTriggerAnalogEnabled/DigitalEnabled`), stick
    calibration and deadzones, gamepad -> key/mouse/touch mapping, macros, gyro.
  - It "subscribes" to other input devices (`refreshSubscribedInputFds/Ids`),
    identifies devices by unique info (`RsDeviceManage.DeviceUniqInfo`), and can
    rename its virtual gamepad. Consistent with the single sticky renamed copy
    seen on device. Not proven that this is the copier or what makes it pick
    a device.
  - Leads not yet followed: a resource named `hold_devices_black_list`
    (unknown use; not a live Settings key on this device).
  - Bypass options: input side - select the RAW external node (e.g. the bus-0005
    Switch Pro pad) instead of RsMapping's copy (needs our own normalization:
    RX/RY vs Z/RZ, digital triggers - triggers already handled). Output side -
    still need to learn when it adopts our device (seems to be only when no
    other external pad is connected).

### 2026-09-29 (e) — digital-trigger synthesis + ambiguous device names (Claude Code)

- Luke's 8BitDo (Bluetooth, Switch mode) forwarded as source works in Dolphin
  except the shoulder triggers. Cause: it sends only digital BTN_TL2/TR2, while
  emulators read analog ABS_BRAKE/ABS_GAS.
- Daemon now synthesizes ABS_BRAKE (left) / ABS_GAS (right) from BTN_TL2/TR2,
  instant 0/max (no ramping), until the source is seen sending that real analog
  axis. Declared axes are NOT trusted: the vendor's virtual "Nintendo Switch Pro
  Controller" declares GAS/BRAKE but never drives them. The virtual device
  always declares GAS/BRAKE (0..32767) even for a digital-only first source.
  **Written and builds; not yet confirmed in Dolphin.**
- **Two devices can share a name.** The Retroid service creates a virtual
  "Nintendo Switch Pro Controller" (bus 0003, 2022:3001 v0064, Z/RZ layout,
  same ranges as the onboard pad) alongside the real BT pad (bus 0005,
  057e:2009, ABS_RX/RY). Matching is now name + `bus:vendor:product:version`
  (SOURCE/SNIFF take `name<TAB>id`; the app passes both). Earlier "8BitDo"
  sniff results were actually the vendor's virtual copy (event11). The real
  pad node (event9) disappeared mid-session (BT sleep?) and wasn't sniffed.
  Open question: use the vendor's virtual copy (already normalized) or the
  raw BT node (needs our own normalization)? Depends on whether the vendor copy
  is stable/always present.

- **Confirmed by Luke in Dolphin:** with the Pro Controller as source the
  shoulders work (binary); switching back to the Retroid source keeps its
  analog triggers working.
- **TODO: rumble.** Nothing forwards force feedback yet. The Retroid's own
  motor (`qcom-hv-haptics`, FF_RUMBLE) buzzes while a BT pad is in use, and
  games get no rumble on the BT pad through our virtual device. Needs: declare
  FF_RUMBLE on the virtual device, handle uinput UI_FF_UPLOAD/ERASE, and route
  effects to the *active* source's FF (BT pad or the haptics node), not the
  handheld's motor. Resolved: the handheld buzzes because Dolphin's rumble motor is set to
  `Android/0/Device Sensors:Motor 0` (the handheld's own vibrator), not because of
  us. Dolphin lists motors per input device, so declaring FF_RUMBLE on the
  virtual device should make a CouchMode motor selectable (expected, unverified).
- Retroid-specific vs generic (Luke's question): matching, source switching,
  and event forwarding are generic. Still Retroid-shaped: default source name,
  virtual device capabilities copied from the first source (should become a
  fixed canonical layout), and reliance on the vendor's virtual pad copy.

### 2026-09-29 (d) — app <-> daemon channel (Claude Code)

- Channel: abstract unix socket `@couchmode`, line-based text protocol
  (documented at the top of `daemon/gamepad_merger.c`): PING, STATUS, LIST,
  SOURCE <name>, SNIFF <name> (streams raw key/abs events, works on the grabbed
  source too), STOP. Works identically for a shell- or root-started daemon; no
  files. The daemon refuses a second instance (socket name taken) and, with
  `-u <uid>`, only accepts the app's uid plus root/shell (SO_PEERCRED).
- Daemon rewritten around one poll loop (listener + source + clients).
- App: `daemon/DaemonClient.kt`, `daemon/EvdevNames.kt`, temporary
  `ui/DaemonScreen.kt` (status, gamepad device list, Watch raw events, Use as
  source). Replaces the Shizuku status screen in `MainActivity`; Shizuku code
  remains, on hold.
- `tools/couchmode-start.sh`: starts the daemon from the installed app's lib
  dir with the app's uid. Verified when run via `adb shell` as the shell user;
  NOT yet tried through the root menu (`pm` under the root context is unknown).
- Verified over adb with a throwaway C client: PING/STATUS/LIST work, LIST shows
  "Nintendo Switch Pro Controller" (Luke's 8BitDo, Bluetooth, Switch mode,
  reports 0003:2022:3001:0064 - same VID:PID as the Retroid's own pads, so
  match by name). SNIFF returned OK; button events not yet seen.
- Known limitation: the virtual device's capabilities/axis ranges are copied
  from the FIRST source. Forwarding another controller (e.g. the 8BitDo) needs
  per-controller normalization to the canonical layout (the wizard's job).
- Next: try the app's Watch screen on the 8BitDo, run the start script through
  the root menu, then multi-source priority + normalization.

### 2026-09-29 (c) — CI green, daemon wired into Gradle (Claude Code)

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
