# Device test helpers

These drive fake input on a real handheld, so they follow the rules in CLAUDE.md
("Testing on the device: fake input is REAL input").

- `vgrab.c` - takes EXCLUSIVE control of the "CouchMode Virtual Gamepad" node (EVIOCGRAB) so Android
  receives none of its events, signals readiness through a file, then prints what the virtual
  gamepad emits. Start it before any fake pad speaks.
- `uitest4.c` - a fake pad that creates its device and then emits NOTHING until a "go" file exists.
- `dpad_test.sh` - example of the safe sequence: make the fake pad the active source, wait until the
  daemon has attached (and so grabbed) it, grab the virtual gamepad, then create the go file. It
  restores the priority list and removes the test map afterwards.

Build for the device with the NDK (`aarch64-linux-android26-clang file.c -o name`), push to
`/data/local/tmp`, and run with `adb shell`. The daemon socket client `cc` used by the script is a
few-line abstract-socket client (connect to `@couchmode`, send lines, print replies).
