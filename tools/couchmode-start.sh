#!/bin/sh

# Starts the CouchMode daemon from the installed app's native lib dir.
# Run via Settings -> "Run script as Root" (or from `adb shell`).
# Push under a brand-new filename each time (see CLAUDE.md, root-menu overlay gotcha):
#   adb push tools/couchmode-start.sh /sdcard/Download/couchmode-start-N.sh
#
# The daemon only accepts app connections from the app's uid (plus root/shell).
# Log: /data/local/tmp/couchmode-daemon.log

pkill -f "[l]ibgamepad_merger"
setsid nohup sh -c 'D=$(dirname $(pm path com.couchmode.app | head -n 1 | cut -d: -f2))/lib/arm64; U=$(pm list packages -U com.couchmode.app | sed "s/.*uid://"); exec $D/libgamepad_merger.so -u $U' > /data/local/tmp/couchmode-daemon.log 2>&1 &
sleep 2
chmod 644 /data/local/tmp/couchmode-daemon.log
