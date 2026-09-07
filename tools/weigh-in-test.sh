#!/usr/bin/env bash
# One live weigh-in, fully instrumented.
#
# Run it, step on the scale when told, and it prints what the app actually did:
# whether a session ran, how it ended, and whether a reading was stored and
# delivered. Everything it reports is read back off the phone — nothing here
# infers or assumes an outcome.
#
#   ./tools/weigh-in-test.sh [seconds-to-watch]   (default 120)
#   ./tools/weigh-in-test.sh --install [seconds]   builds and installs first
#   ANDROID_SERIAL=<serial> ./tools/weigh-in-test.sh   when more than one phone is attached
#     (Pixel 10 Pro Fold — the bridge — is 57211FDCG0023C; the retired Pixel 9 is 4A111FDKD0000C)
#
# --install uses `adb install -r`, which preserves app data. Never uninstall:
# the encrypted stores are keyed to the install, so uninstalling destroys the
# registered scale profile and burns one of the BF720's eight user slots to
# get it back.

set -uo pipefail

PKG=com.ventouxlabs.bascule
INSTALL=0
if [ "${1:-}" = "--install" ]; then INSTALL=1; shift; fi
WATCH=${1:-120}
OUT=$(mktemp -d)
trap 'kill "${LOGCAT_PID:-}" 2>/dev/null' EXIT

command -v adb >/dev/null || { echo "adb not found on PATH"; exit 1; }
if [ -z "${ANDROID_SERIAL:-}" ] && [ "$(adb devices | awk 'NR>1 && $2=="device"' | wc -l)" -gt 1 ]; then
  echo "More than one phone is attached. Set ANDROID_SERIAL=<serial> (adb devices lists them)."; exit 1
fi
adb get-state >/dev/null 2>&1 || { echo "No device. Plug the phone in and enable USB debugging."; exit 1; }

if [ "$INSTALL" = "1" ]; then
  echo "=== Building and installing (data preserved) ==="
  ./gradlew :app:assembleDebug -q || { echo "build failed"; exit 1; }
  adb install -r app/build/outputs/apk/debug/app-debug.apk || { echo "install failed"; exit 1; }
  # A fresh install leaves the app stopped; the bridge service only comes back
  # once something starts the process.
  adb logcat -c 2>/dev/null
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 5
  # The Scale screen gained a new construction path this session
  # (CaptureAttemptLog opens SharedPreferences and registers a listener from
  # BasculeApplication's lazy). Nothing in the JVM lane can prove that opens,
  # so prove it here before trusting anything else this script reports.
  if adb logcat -d -b crash -t 200 2>/dev/null | grep -q "$PKG"; then
    echo "  !! The app CRASHED on launch. Stop here and report this:"
    adb logcat -d -b crash -t 200 2>/dev/null | grep -A 15 "$PKG" | head -25 | sed 's/^/    /'
    exit 1
  fi
  if adb shell ps -A 2>/dev/null | grep -q "$PKG"; then
    echo "  App launched cleanly (no crash). Open the Scale tab and confirm it renders."
  else
    echo "  !! The app is not running after launch — check manually before continuing."
  fi
  echo
fi

# A release build is not debuggable, so `run-as` (the app's private files)
# is off limits; everything the script reads that way degrades to a note.
# logcat and the app's own Scale tab still say what happened.
RUN_AS_OK=1
adb shell run-as "$PKG" true >/dev/null 2>&1 || { RUN_AS_OK=0; echo "note: non-debuggable build — on-device files are not readable, showing logcat only"; }

echo "=== Preflight ==="
adb shell dumpsys package "$PKG" | grep -q "versionName" || { echo "$PKG is not installed."; exit 1; }
BT=$(adb shell settings get global bluetooth_on | tr -d '\r')
[ "$BT" = "1" ] && echo "  Bluetooth: on" || echo "  Bluetooth: OFF — turn it on before continuing."
echo "  Standby bucket: $(adb shell am get-standby-bucket "$PKG" | tr -d '\r')  (10=active, higher=more throttled)"
if adb shell dumpsys activity services "$PKG" 2>/dev/null | grep -q BridgeForegroundService; then
  echo "  Bridge service: running"
else
  echo "  Bridge service: NOT running — open the app once, or enable always-on bridging."
fi
echo "  Cooldown entries currently held (an entry here blocks new sessions for that address):"
[ "$RUN_AS_OK" = 1 ] && { adb shell run-as "$PKG" cat "/data/data/$PKG/shared_prefs/scan_enqueue_cooldown.xml" 2>/dev/null \
  | grep -o 'name="[^"]*"' | sed 's/^/    /' || echo "    (none)"; } || echo "    (not readable on this build)"

echo
echo "=== Recording ==="
adb logcat -c 2>/dev/null
adb logcat -v time > "$OUT/full.log" 2>/dev/null &
LOGCAT_PID=$!

echo
echo "  >>> STEP ON THE SCALE NOW. Stay on until it shows your weight, then step off. <<<"
echo "  Watching for ${WATCH}s..."
for i in $(seq "$WATCH" -10 10); do printf "\r  %3ds remaining " "$i"; sleep 10; done
printf "\r                        \n"

kill "$LOGCAT_PID" 2>/dev/null; wait "$LOGCAT_PID" 2>/dev/null

echo
echo "=== What the app did ==="
grep -E "ScaleSessionWorker|ScanBroadcastReceiver|BasculeApplication|BridgeForegroundService" "$OUT/full.log" \
  | grep -vE "adbd|dumpsys" | tail -40 || echo "  (no app log lines — the app was never woken)"

echo
echo "=== Last recorded capture attempt (survives the worker process) ==="
[ "$RUN_AS_OK" = 1 ] && { adb shell run-as "$PKG" cat "/data/data/$PKG/shared_prefs/capture_attempts.xml" 2>/dev/null \
  | grep -E "last_outcome|last_at_millis" | sed 's/^/  /' || echo "  (no attempt has ever been recorded)"; } || echo "  (not readable on this build — open the Scale tab: 'Last attempt')"

echo
echo "=== Stored readings ==="
[ "$RUN_AS_OK" = 1 ] || { echo "  (not readable on this build — open History)"; echo; echo "Full log: $OUT/full.log"; trap - EXIT; exit 0; }
adb shell "run-as $PKG cat /data/data/$PKG/databases/bascule.db"     > "$OUT/bascule.db"     2>/dev/null
adb shell "run-as $PKG cat /data/data/$PKG/databases/bascule.db-wal" > "$OUT/bascule.db-wal" 2>/dev/null
if command -v sqlite3 >/dev/null; then
  sqlite3 -header -column "$OUT/bascule.db" \
    "select datetime(capturedAtMillis/1000,'unixepoch','localtime') as captured,
            round(weightKg,2) as kg, status, attemptCount, source,
            coalesce(substr(lastError,1,40),'') as err
     from readings order by capturedAtMillis desc limit 5;" 2>/dev/null | sed 's/^/  /'
else
  echo "  (install sqlite3 to see the readings table)"
fi

echo
echo "Full log: $OUT/full.log"
trap - EXIT
