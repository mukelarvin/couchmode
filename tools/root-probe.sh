#!/bin/sh

# CouchMode root probe, v8. Run via the handheld's Settings -> "Run script as Root".
#
# Modeled on the known-good flash_abl.sh template from armada-os: plain
# sequential commands, no braces, no variables carried between lines, no
# backgrounding. (Background survival was already confirmed in an earlier run.)
#
# Results are appended to one file, then copied to two more locations so at
# least one can be found despite the internal-storage / SD-card overlay:
#   /sdcard/Download/couchmode-v8.txt
#   /sdcard/couchmode-v8.txt
#   /data/local/tmp/couchmode-v8.txt
# Also logs to logcat under the tag "couchmode".

log -t couchmode "probe v8 started"

echo "== v8 started ==" > /sdcard/Download/couchmode-v8.txt
date >> /sdcard/Download/couchmode-v8.txt

echo "== identity ==" >> /sdcard/Download/couchmode-v8.txt
id >> /sdcard/Download/couchmode-v8.txt 2>&1

echo "== selinux ==" >> /sdcard/Download/couchmode-v8.txt
getenforce >> /sdcard/Download/couchmode-v8.txt 2>&1
cat /proc/self/attr/current >> /sdcard/Download/couchmode-v8.txt 2>&1
echo >> /sdcard/Download/couchmode-v8.txt

echo "== uinput nodes ==" >> /sdcard/Download/couchmode-v8.txt
ls -l /dev/uinput /dev/input/uinput >> /sdcard/Download/couchmode-v8.txt 2>&1

echo "== uinput open test ==" >> /sdcard/Download/couchmode-v8.txt
true 3>/dev/uinput && echo "OPEN OK /dev/uinput" >> /sdcard/Download/couchmode-v8.txt || echo "OPEN FAILED /dev/uinput" >> /sdcard/Download/couchmode-v8.txt

echo "== input nodes ==" >> /sdcard/Download/couchmode-v8.txt
ls -l /dev/input >> /sdcard/Download/couchmode-v8.txt 2>&1

echo "== input devices ==" >> /sdcard/Download/couchmode-v8.txt
cat /proc/bus/input/devices >> /sdcard/Download/couchmode-v8.txt 2>&1

echo "== storage volumes ==" >> /sdcard/Download/couchmode-v8.txt
ls -l /storage >> /sdcard/Download/couchmode-v8.txt 2>&1

echo "== v8 finished ==" >> /sdcard/Download/couchmode-v8.txt

cp /sdcard/Download/couchmode-v8.txt /sdcard/couchmode-v8.txt
cp /sdcard/Download/couchmode-v8.txt /data/local/tmp/couchmode-v8.txt
chmod 644 /sdcard/Download/couchmode-v8.txt /sdcard/couchmode-v8.txt /data/local/tmp/couchmode-v8.txt
sync

log -t couchmode "probe v8 finished"
