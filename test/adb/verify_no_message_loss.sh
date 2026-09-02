#!/usr/bin/env bash
# Verify that every message the phone sends actually reaches the session.
#
# The failure this guards against: a half-open socket accepts writes silently,
# so the app reports "sent" while the orchestrator never receives the frame.
# The retry layer re-sends until its budget is exhausted and the message is
# dropped with nothing the user ever sees.
#
# The RC send path is driven by the UI, not by the ADB command receiver, so the
# check runs as an instrumented test. Each message asks the model to echo a
# unique marker: a reply carrying that marker proves the message travelled the
# whole path -- phone, orchestrator, CLI -- and came back. Local queueing cannot
# fake that.
#
# Exit 0 = no loss. Non-zero = a message was lost, or the preconditions were
# not met (a failure to measure, not a pass).
set -uo pipefail

SERIAL="${ANDROID_SERIAL:-65TKQWDIEIL7W8LF}"
PKG="com.repository.listener"
WORK_DIR="/tmp/rc-test-msgloss"

adb() { command adb -s "$SERIAL" "$@"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

# --- preconditions -------------------------------------------------------

adb get-state >/dev/null 2>&1 || fail "device $SERIAL not reachable"

# With the VPN up the traffic takes a different path and this proves nothing.
if adb shell ip -brief addr 2>/dev/null | grep -qE '^tun[0-9]+ +UP'; then
  fail "phone VPN appears to be UP -- this check is only meaningful with it off"
fi

# The CLI opens a workspace-trust dialog for an unknown directory and then
# blocks forever, which would look like a hung test rather than a missing
# precondition.
mkdir -p "$WORK_DIR"
python3 - "$WORK_DIR" <<'PY' || exit 1
import json, os, sys
path = sys.argv[1]
cfg = os.path.expanduser('~/.claude.json')
try:
    d = json.load(open(cfg))
except Exception as e:
    print(f"cannot read {cfg}: {e}", file=sys.stderr); sys.exit(1)
e = d.setdefault('projects', {}).setdefault(path, {})
e['hasTrustDialogAccepted'] = True
e.setdefault('allowedTools', [])
e.setdefault('history', [])
json.dump(d, open(cfg, 'w'), indent=2)
PY

# A stale CLI holding this directory would be attached to instead of a fresh
# one, carrying the previous run's state.
for f in "$HOME"/.claude/sessions/*.json; do
  [ -e "$f" ] || continue
  pid=$(python3 -c "
import json,sys
try:
    d=json.load(open('$f'))
    print(d['pid']) if '$WORK_DIR' in (d.get('cwd') or '') else None
except Exception: pass
" 2>/dev/null)
  [ -n "${pid:-}" ] && kill -TERM "$pid" 2>/dev/null
done
sleep 2

adb shell pm list packages 2>/dev/null | grep -q "$PKG.test" \
  || fail "instrumentation APK not installed -- build and install app-*-androidTest.apk"

# --- run -----------------------------------------------------------------

adb logcat -c >/dev/null 2>&1

OUT=$(adb shell am instrument -w -r \
  -e class com.repository.listener.ui.rc.RcMessageDeliveryTest \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" 2>&1)

if printf '%s\n' "$OUT" | grep -q 'OK ('; then
  # Retry exhaustion is a silent drop even if the replies happened to arrive.
  GAVE_UP=$(adb logcat -d -v time OrchestratorClient:* '*:S' 2>/dev/null \
    | grep -c "gave up after" || true)
  if [ "$GAVE_UP" -gt 0 ]; then
    adb logcat -d -v time OrchestratorClient:* '*:S' 2>/dev/null | grep "gave up after" | tail -5 >&2
    fail "$GAVE_UP message(s) exhausted their retry budget"
  fi
  echo "PASS: every message was delivered and answered, no retry exhaustion"
  exit 0
fi

printf '%s\n' "$OUT" | grep -E "AssertionError|Message loss|IllegalState|Tests run" | head -8 >&2
fail "message delivery test did not pass"
