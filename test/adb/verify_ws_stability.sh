#!/usr/bin/env bash
# Verify the phone holds its orchestrator connection for 10 minutes.
#
# The failure this guards against: the socket goes one-way, the phone notices
# only via a ping timeout, reconnects, and repeats -- roughly every 30-60s.
# Messages sent during a stall are lost, and a long tool never gets the frame
# saying it finished.
#
# Passes only if the app connects once and stays connected. Any reconnect fails
# the run, because there is nothing deliberate happening during the window: no
# deploy, no force-stop, no airplane mode. A reconnect here is by definition
# unexplained.
#
# Exit 0 = stable. Non-zero = a stall was observed, or the preconditions were
# not met (which is a failure to measure, not a pass).
set -uo pipefail

SERIAL="${ANDROID_SERIAL:-65TKQWDIEIL7W8LF}"
PKG="com.repository.listener"
WINDOW_SEC="${WS_STABILITY_WINDOW_SEC:-600}"

adb() { command adb -s "$SERIAL" "$@"; }

fail() { echo "FAIL: $*" >&2; exit 1; }

# --- preconditions -------------------------------------------------------

adb get-state >/dev/null 2>&1 || fail "device $SERIAL not reachable"

# The VPN must be OFF: with it on this test proves nothing, because the traffic
# takes an entirely different path.
if adb shell ip -brief addr 2>/dev/null | grep -qE '^tun[0-9]+ +UP'; then
  fail "phone VPN appears to be UP -- this check is only meaningful with it off"
fi

adb shell pidof "$PKG" >/dev/null 2>&1 || {
  adb shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
  sleep 15
}
adb shell pidof "$PKG" >/dev/null 2>&1 || fail "$PKG is not running"

# --- observe -------------------------------------------------------------

echo "Observing for ${WINDOW_SEC}s (VPN off, no deliberate restarts)..."
adb logcat -c >/dev/null 2>&1

# Wait for the initial connection so its "connected" line is not counted as a
# reconnect, then start the clock.
for _ in $(seq 1 30); do
  if adb logcat -d -v time OrchestratorClient:I '*:S' 2>/dev/null | grep -q "connected to"; then
    break
  fi
  sleep 2
done
adb logcat -c >/dev/null 2>&1

sleep "$WINDOW_SEC"

LOG=$(adb logcat -d -v time OrchestratorClient:* '*:S' 2>/dev/null)
RECONNECTS=$(printf '%s\n' "$LOG" | grep -c "connected to" || true)
STALLS=$(printf '%s\n' "$LOG" | grep -c "half-open" || true)
PING_TIMEOUTS=$(printf '%s\n' "$LOG" | grep -c "didn't receive pong" || true)

echo "window=${WINDOW_SEC}s reconnects=$RECONNECTS stalls=$STALLS ping_timeouts=$PING_TIMEOUTS"

# The app must still be alive: a crash would produce no reconnects and would
# otherwise read as a pass.
adb shell pidof "$PKG" >/dev/null 2>&1 || fail "$PKG died during the window"

if [ "$RECONNECTS" -gt 0 ] || [ "$STALLS" -gt 0 ] || [ "$PING_TIMEOUTS" -gt 0 ]; then
  echo "--- evidence ---" >&2
  printf '%s\n' "$LOG" | grep -E "connected to|half-open|didn't receive pong" | tail -20 >&2
  fail "connection was not stable: $RECONNECTS reconnect(s), $STALLS stall(s), $PING_TIMEOUTS ping timeout(s)"
fi

echo "PASS: connection stable for ${WINDOW_SEC}s with no reconnects"
