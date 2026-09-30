# D-pad translation test. SAFE by construction:
#  - the fake pad emits nothing until /data/local/tmp/go exists;
#  - we only create that file after (a) the daemon has attached the fake pad (so it is grabbed) and
#    (b) vgrab holds EXCLUSIVE control of the virtual gamepad (so Android receives nothing).
CC=/data/local/tmp/cc
T=$(printf '\t')
GO=/data/local/tmp/go
READY=/data/local/tmp/vgrab.ready
cp /data/local/tmp/couchmode-priority.conf /data/local/tmp/priority.before
cp /data/local/tmp/couchmode-maps.conf /data/local/tmp/maps.before 2>/dev/null
cleanup() {
  pkill -x uitest4 2>/dev/null; pkill -x vgrab 2>/dev/null
  $CC 1 "SETMAP${T}TestDpad${T}${T}${T}raw${T}" >/dev/null 2>&1
  cp /data/local/tmp/priority.before /tmp/p.restore 2>/dev/null
  # restore the user's priority list through the daemon
  REST="PRIORITY"
  while IFS= read -r line; do
    case "$line" in @*|"") continue;; esac
    n=$(echo "$line" | cut -f1); i=$(echo "$line" | cut -f2); u=$(echo "$line" | cut -f3)
    REST="${REST}${T}${n}${T}${i}${T}${u}"
  done < /data/local/tmp/priority.before
  $CC 1 "$REST" >/dev/null 2>&1
  rm -f $GO $READY
}
trap cleanup EXIT

run() {  # name mode setmap-pairs codes...
  NAME=$1; MODE=$2; PAIRS=$3; shift 3
  rm -f $GO $READY
  /data/local/tmp/uitest4 $NAME $GO $MODE "$@" >/dev/null 2>&1 &
  sleep 1
  # make the fake pad the top priority, then wait until the daemon has attached (and so grabbed) it
  $CC 1 "PRIORITY${T}${NAME}${T}${T}${T}Retroid Pocket Controller${T}${T}" >/dev/null
  ok=0; for i in 1 2 3 4 5 6 7 8 9 10; do
    if [ "$($CC 1 STATUS | cut -f2,3)" = "$NAME${T}1" ]; then ok=1; break; fi; sleep 0.5; done
  [ $ok = 1 ] || { echo "ABORT: daemon never attached $NAME"; return 1; }
  $CC 1 "SETMAP${T}${NAME}${T}${T}${T}raw${T}${PAIRS}" | head -1 >/dev/null
  # take exclusive control of the virtual gamepad's output, and only then let the fake pad speak
  /data/local/tmp/vgrab 8 $READY > /data/local/tmp/vgrab.out 2>&1 &
  for i in 1 2 3 4 5 6 7 8 9 10; do [ -f $READY ] && break; sleep 0.3; done
  [ -f $READY ] || { echo "ABORT: could not take exclusive control of the virtual gamepad"; pkill -x uitest4; return 1; }
  touch $GO
  wait
  echo "--- virtual gamepad output (type code value; 3=ABS, 1=KEY; HAT0X=16 HAT0Y=17):"
  cat /data/local/tmp/vgrab.out | tr '\n' ';'; echo
  pkill -x uitest4 2>/dev/null
  sleep 1
}

echo "=== 1) four plain BUTTONS (codes 257..260) act as D-pad up, down, left, right"
run TestDpad keys "257=1000,258=1001,259=1002,260=1003" 257 258 259 260
echo "    expected: HAT0Y -1,0 (up); HAT0Y 1,0 (down); HAT0X -1,0 (left); HAT0X 1,0 (right)"

echo "=== 2) a hat held SIDEWAYS: HAT0X- -> up, HAT0X+ -> down, HAT0Y- -> left, HAT0Y+ -> right"
run TestDpad hat "10032=1000,10033=1001,10034=1002,10035=1003"
echo "    expected: hat X -1,0,+1,0 gives HAT0Y -1,0,+1,0 ; then hat Y -1,0,+1,0 gives HAT0X -1,0,+1,0"
