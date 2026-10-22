#!/usr/bin/env bash
# End-to-end check of the real alarm path on a connected device (SPEC §10, M1).
#
#   scripts/e2e-alarm.sh                  ring with screen off + locked, in forced Doze, then dismiss
#   scripts/e2e-alarm.sh --timeout        also: an undismissed ring times out and releases everything
#   scripts/e2e-alarm.sh --reboot         also: an alarm due while rebooting rings before unlock
#   scripts/e2e-alarm.sh --power-off      also: rebooting mid-ring resumes the ring (CHEAT_DETECTED)
#   scripts/e2e-alarm.sh --wake-check     also: wake-up check after dismissal; missing it rings again
#   scripts/e2e-alarm.sh --all            everything (about 14 minutes)
#
# Requirements: a debug build (./gradlew assembleDebug), USB debugging on, a secure lock screen,
# and for the reboot tests: do NOT unlock the phone until the script says so.
set -uo pipefail
export MSYS_NO_PATHCONV=1 # Git Bash on Windows: don't rewrite /paths in adb arguments

PKG=com.cascade.alarm
APK=app/build/outputs/apk/debug/app-debug.apk
if [[ -z "${ADB:-}" ]]; then
  if command -v adb >/dev/null; then ADB=adb
  elif [[ -n "${LOCALAPPDATA:-}" ]]; then ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
  else ADB="$HOME/Android/Sdk/platform-tools/adb"; fi
fi

RUN_TIMEOUT=0; RUN_REBOOT=0; RUN_POWER_OFF=0; RUN_WAKE_CHECK=0
for arg in "$@"; do
  case "$arg" in
    --timeout) RUN_TIMEOUT=1 ;;
    --reboot) RUN_REBOOT=1 ;;
    --power-off) RUN_POWER_OFF=1 ;;
    --wake-check) RUN_WAKE_CHECK=1 ;;
    --all) RUN_TIMEOUT=1; RUN_REBOOT=1; RUN_POWER_OFF=1; RUN_WAKE_CHECK=1 ;;
    *) echo "unknown option $arg"; exit 2 ;;
  esac
done

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
info() { echo; echo "== $*"; }
sh() { "$ADB" shell "$@" | tr -d '\r'; }
debug_cmd() { sh am broadcast -a "com.cascade.alarm.debug.$1" -p "$PKG" "${@:2}" >/dev/null; }
# Capture output before grepping: `producer | grep -q` under pipefail fails at random when grep
# exits early and the producer gets SIGPIPE.
log_has() { local out; out=$("$ADB" logcat -d -s CascadeEngine:I | tr -d '\r'); grep -q -- "$1" <<<"$out"; }
# Only the live "Wake Locks:" table; dumpsys power also prints a history of released locks.
ring_lock_held() {
  local out; out=$(sh dumpsys power)
  sed -n '/^Wake Locks: size/,/^$/p' <<<"$out" | grep -q "'cascade:ringing'"
}
screen_awake() { local out; out=$(sh dumpsys power); grep -q "mWakefulness=Awake" <<<"$out"; }
alarm_activity_in_front() {
  local out; out=$(sh dumpsys activity activities)
  grep -E "topResumedActivity|ResumedActivity" <<<"$out" | grep -q "AlarmActivity"
}

wait_for_log() { # pattern, timeout seconds
  local deadline=$((SECONDS + $2))
  while (( SECONDS < deadline )); do log_has "$1" && return 0; sleep 2; done
  return 1
}

check() { # description, pattern-in-logcat
  if log_has "$2"; then pass "$1"; else fail "$1 (no '$2' in logcat)"; fi
}

assert_released() {
  sleep 3
  if ring_lock_held; then fail "ringing wake lock still held"; else pass "wake lock released"; fi
  local services; services=$(sh dumpsys activity services "$PKG")
  if grep -q "AlarmRingService" <<<"$services"; then
    fail "ring service still running"
  else
    pass "ring service stopped"
  fi
}

screen_off_and_doze() {
  sh input keyevent KEYCODE_SLEEP
  sleep 2
  sh dumpsys battery unplug
  local idle; idle=$(sh dumpsys deviceidle force-idle)
  if [[ "$idle" == *"deep idle"* ]]; then pass "device forced into Doze"; else fail "could not force Doze: $idle"; fi
}

restore_device() {
  sh dumpsys deviceidle unforce >/dev/null 2>&1
  sh dumpsys battery reset >/dev/null 2>&1
}
trap restore_device EXIT

wait_for_boot() {
  "$ADB" wait-for-device
  until [[ "$(sh getprop sys.boot_completed)" == "1" ]]; do sleep 2; done
  sleep 5
}

# ---------------------------------------------------------------------------------------------
info "Setup"
"$ADB" get-state >/dev/null 2>&1 || { echo "No device connected."; exit 1; }
SDK=$(sh getprop ro.build.version.sdk)
echo "  device: $(sh getprop ro.product.manufacturer) $(sh getprop ro.product.model), API $SDK"
[[ -f "$APK" ]] || { echo "Build first: ./gradlew assembleDebug"; exit 1; }
"$ADB" install -r "$APK" >/dev/null && pass "installed $APK"
(( SDK >= 33 )) && sh pm grant "$PKG" android.permission.POST_NOTIFICATIONS
(( SDK >= 34 )) && sh appops set "$PKG" USE_FULL_SCREEN_INTENT allow
# Reconciles on start. Retried: right after install the package manager may not
# have registered the activity yet ("Activity class ... does not exist").
for _ in 1 2 3 4 5; do
  started=$(sh am start -W -n "$PKG/.MainActivity" 2>&1)
  grep -q "Error" <<<"$started" || break
  sleep 2
done
grep -q "Error" <<<"$started" && fail "could not launch the app: $started"
sleep 4
debug_cmd SET_TIMEOUT --ei minutes 15
debug_cmd DISABLE
debug_cmd SET_WAKE_CHECK --ez enabled false

# ---------------------------------------------------------------------------------------------
info "Ring: locked, screen off, Doze"
"$ADB" logcat -c
debug_cmd RING_IN --ei seconds 40
sleep 2
check "scheduled with setAlarmClock" "setAlarmClock alarm=system-test-alarm"
screen_off_and_doze
if wait_for_log "ringing alarm=system-test-alarm" 75; then pass "alarm rang in Doze"; else fail "alarm did not ring within 75s"; fi
check "receiver fired" "fire received alarm=system-test-alarm"
check "audio started on the alarm stream" "audio started"
if log_has "systemExempted refused"; then fail "systemExempted FGS type refused (fell back to mediaPlayback)"; else pass "systemExempted foreground service"; fi
sleep 3
if screen_awake; then pass "screen turned on"; else fail "screen did not turn on"; fi
if alarm_activity_in_front; then pass "AlarmActivity shown over the lock screen"; else fail "AlarmActivity not in front"; fi
WINDOWS=$(sh dumpsys window)
if grep -q "isKeyguardShowing=true" <<<"$WINDOWS"; then
  pass "keyguard still locked underneath (no PIN prompt forced)"
else
  fail "keyguard is not showing under the alarm"
fi
if ring_lock_held; then pass "wake lock held while ringing"; else fail "no wake lock while ringing"; fi
debug_cmd DISMISS
wait_for_log "event DISMISSED" 10 && pass "dismissed" || fail "dismiss not logged"
assert_released
restore_device

# ---------------------------------------------------------------------------------------------
if (( RUN_TIMEOUT )); then
  info "Timeout: ring is never dismissed (1-minute timeout)"
  "$ADB" logcat -c
  debug_cmd SET_TIMEOUT --ei minutes 1
  debug_cmd RING_IN --ei seconds 15
  sh input keyevent KEYCODE_SLEEP
  wait_for_log "ringing alarm=system-test-alarm" 45 && pass "rang" || fail "did not ring"
  if wait_for_log "\"reason\":\"timeout\"" 90; then pass "timed out and logged MISSED"; else fail "no timeout within 90s"; fi
  assert_released
  debug_cmd SET_TIMEOUT --ei minutes 15
fi

# ---------------------------------------------------------------------------------------------
if (( RUN_REBOOT )); then
  info "Reboot: alarm due 150s from now; phone reboots and stays LOCKED (do not unlock)"
  debug_cmd RING_IN --ei seconds 150
  sleep 2
  "$ADB" reboot
  wait_for_boot
  pass "device back up (still locked)"
  check "schedule rebuilt at LOCKED_BOOT_COMPLETED" "reconcile reason=LOCKED_BOOT_COMPLETED"
  if wait_for_log "ringing alarm=system-test-alarm" 180; then pass "alarm rang after reboot, before unlock"; else fail "alarm did not ring after reboot"; fi
  sleep 3
  if screen_awake && alarm_activity_in_front; then pass "alarm screen shown after reboot"; else fail "alarm screen not shown after reboot"; fi
  debug_cmd DISMISS
  wait_for_log "event DISMISSED" 10 && pass "dismissed" || fail "dismiss not logged"
  assert_released
fi

# ---------------------------------------------------------------------------------------------
if (( RUN_POWER_OFF )); then
  info "Power-off during a ring: the ring must resume after boot"
  "$ADB" logcat -c
  debug_cmd RING_IN --ei seconds 15
  wait_for_log "ringing alarm=system-test-alarm" 45 && pass "rang" || fail "did not ring"
  "$ADB" reboot
  wait_for_boot
  if wait_for_log "event CHEAT_DETECTED" 60; then pass "CHEAT_DETECTED logged"; else fail "no CHEAT_DETECTED after boot"; fi
  if wait_for_log "ringing alarm=system-test-alarm" 30; then pass "ring resumed before unlock"; else fail "ring did not resume"; fi
  sleep 3
  if screen_awake && alarm_activity_in_front; then pass "alarm screen shown after power-off"; else fail "alarm screen not shown after power-off"; fi
  debug_cmd DISMISS
  wait_for_log "event DISMISSED" 10 && pass "dismissed" || fail "dismiss not logged"
  assert_released
fi

# ---------------------------------------------------------------------------------------------
if (( RUN_WAKE_CHECK )); then
  info "Wake-up check: 1 min after dismissal; the first check is ignored, the second answered"
  "$ADB" logcat -c
  debug_cmd SET_WAKE_CHECK --ez enabled true --ei minutes 1
  debug_cmd RING_IN --ei seconds 15
  wait_for_log "ringing alarm=system-test-alarm deadline=.* wakeCheck=false" 45 && pass "rang" || fail "did not ring"
  debug_cmd DISMISS
  wait_for_log "event DISMISSED" 10 && pass "dismissed" || fail "dismiss not logged"
  check "check scheduled after the last alarm" "\"reason\":\"wake_check\""
  sh input keyevent KEYCODE_SLEEP
  if wait_for_log "wakeCheck=true" 90; then pass "check appeared about a minute later"; else fail "no wake-up check within 90s"; fi
  check "check only vibrates" "wake check vibration started"
  sleep 3
  if screen_awake && alarm_activity_in_front; then pass "check shown over the lock screen"; else fail "check screen not shown"; fi
  "$ADB" logcat -c
  if wait_for_log "event WAKE_CHECK_FAILED" 75; then pass "unanswered check failed after a minute"; else fail "check did not time out"; fi
  if wait_for_log "audio started" 10; then pass "alarm rang again with sound"; else fail "alarm did not ring again"; fi
  debug_cmd DISMISS
  wait_for_log "event DISMISSED" 10 && pass "repeat ring dismissed" || fail "repeat dismiss not logged"
  "$ADB" logcat -c
  if wait_for_log "wakeCheck=true" 90; then pass "another check followed the repeat ring"; else fail "no second check"; fi
  debug_cmd DISMISS
  wait_for_log "event WAKE_CHECK_PASSED" 10 && pass "answered check passed" || fail "pass not logged"
  assert_released
  "$ADB" logcat -c
  sleep 70
  if log_has "ringing alarm="; then fail "something rang after the check was passed"; else pass "nothing rang after passing"; fi
  debug_cmd SET_WAKE_CHECK --ez enabled false
fi

echo
if (( FAILURES == 0 )); then echo "ALL CHECKS PASSED"; else echo "$FAILURES CHECK(S) FAILED"; fi
exit $(( FAILURES > 0 ))
