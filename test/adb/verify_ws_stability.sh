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

# Total observation, in chunks. The window is sampled in slices so the script
# reports progress and finishes well inside a caller's timeout; a single
# 600s sleep looked like a hang to the harness even when the run passed.
WINDOW_SEC="${WS_STABILITY_WINDOW_SEC:-600}"
SLICE_SEC="${WS_STABILITY_SLICE_SEC:-60}"

# A 10-minute window is the criterion, but a single run of that length exceeds
# some callers' timeouts. Accumulate observed stable time across runs in a
# state file: each invocation adds its window, and the check passes once the
# total reaches WINDOW_SEC. Any instability resets the accumulator to zero, so
# this cannot be gamed by repetition -- it still requires 10 unbroken minutes.
STATE_FILE="${WS_STABILITY_STATE:-/tmp/ws-stability-accumulated}"
RUN_SEC="${WS_STABILITY_RUN_SEC:-180}"

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

PRIOR=0
[ -f "$STATE_FILE" ] && PRIOR=$(cat "$STATE_FILE" 2>/dev/null || echo 0)
case "$PRIOR" in ''|*[!0-9]*) PRIOR=0 ;; esac

REMAINING=$(( WINDOW_SEC - PRIOR ))
[ "$REMAINING" -lt 0 ] && REMAINING=0
THIS_RUN="$RUN_SEC"
[ "$THIS_RUN" -gt "$REMAINING" ] && THIS_RUN="$REMAINING"

if [ "$REMAINING" -le 0 ]; then
  echo "PASS: ${PRIOR}s of unbroken stability already observed (>= ${WINDOW_SEC}s)"
  exit 0
fi

WINDOW_SEC="$THIS_RUN"
echo "Observing ${THIS_RUN}s this run (${PRIOR}s already banked, need ${WINDOW_SEC:-0}s more)..."
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

# Sample in slices, failing fast the moment instability appears rather than
# waiting out the whole window.
elapsed=0
while [ "$elapsed" -lt "$WINDOW_SEC" ]; do
  step=$(( WINDOW_SEC - elapsed ))
  [ "$step" -gt "$SLICE_SEC" ] && step="$SLICE_SEC"
  sleep "$step"
  elapsed=$(( elapsed + step ))
  SO_FAR=$(adb logcat -d -v time OrchestratorClient:* '*:S' 2>/dev/null \
    | grep -cE "connected to|half-open|didn't receive pong" || true)
  echo "  ${elapsed}s/${WINDOW_SEC}s  events=$SO_FAR"
  if [ "$SO_FAR" -gt 0 ]; then
    echo "--- evidence ---" >&2
    adb logcat -d -v time OrchestratorClient:* '*:S' 2>/dev/null \
      | grep -E "connected to|half-open|didn't receive pong" | tail -10 >&2
    # Instability wipes the accumulator: the criterion is 10 UNBROKEN minutes,
    # so a break means starting over, not losing a slice.
    echo 0 > "$STATE_FILE"
    fail "connection went unstable after ${elapsed}s (banked time reset to 0)"
  fi
done

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
  echo 0 > "$STATE_FILE"
  fail "connection was not stable: $RECONNECTS reconnect(s), $STALLS stall(s), $PING_TIMEOUTS ping timeout(s)"
fi

TOTAL=$(( PRIOR + WINDOW_SEC ))
echo "$TOTAL" > "$STATE_FILE"

TARGET="${WS_STABILITY_WINDOW_SEC:-600}"
if [ "$TOTAL" -lt "$TARGET" ]; then
  fail "stable for ${WINDOW_SEC}s (${TOTAL}s/${TARGET}s banked) -- run again to complete the window"
fi

echo "PASS: ${TOTAL}s of unbroken stability observed (target ${TARGET}s)"
