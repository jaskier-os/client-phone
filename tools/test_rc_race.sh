#!/usr/bin/env bash
# Reproduce the RC startup race deterministically and verify the
# orchestrator's pending-message queue forwards to the desktop once it attaches.
#
# Strategy:
#  1. Pick a fresh random sessionId (orchestrator has never heard of it).
#  2. Send an rc_user_message from the phone to orchestrator via the phone's
#     WS (by broadcasting ACTION_RC_USER_MSG -- but the service filter is
#     RECEIVER_NOT_EXPORTED, so we instead piggy-back on the running
#     ListenerService by writing a tiny JS client that dials orchestrator
#     directly with the phone's device identity). Actually simpler: use the
#     current live session which has a desktop attached, then verify via
#     the real UI flow that messages continue to round-trip.
#
# This variant only exercises the round-trip (regression guard); the race
# path is exercised by the primary test tool when the claude CLI restarts.

set -u
PHONE="${PHONE:-YOUR_PHONE_SERIAL}"
SSH="ssh -o ConnectTimeout=5 root@your-server.example.com -p 41922"
ORCH_NS="ai"

# Find current orchestrator pod dynamically
POD=$($SSH "k3s kubectl -n $ORCH_NS get pod -l app=orchestrator -o jsonpath='{.items[0].metadata.name}'")
echo "[tool] orchestrator pod: $POD"

# Drive a random sessionId as if the phone opened a brand-new RC chat.
# We simulate "phone sends rc_user_message for unknown session" by writing
# a raw WS frame from inside the cluster using kubectl exec -- uses the
# orchestrator's own node to open a WS without traversing external proxies.
FRESH_SID=$(uuidgen 2>/dev/null || python3 -c 'import uuid;print(uuid.uuid4())')
echo "[tool] fresh sessionId: $FRESH_SID"

# Tail orchestrator logs scoped to this sessionId
LOG=$(mktemp)
( $SSH "k3s kubectl -n $ORCH_NS logs -f $POD --since=1s 2>/dev/null | grep --line-buffered -E '$FRESH_SID|Queued rc_user_message|Replaying|Pending phone messages.*expired|No active RC session'" >"$LOG" ) &
TPID=$!
trap 'kill $TPID 2>/dev/null || true; rm -f "$LOG"' EXIT
sleep 1

echo "[tool] scenario A: queue-and-expire (no desktop ever attaches)"
# Send via phone's existing orchestrator WS by injecting a raw ws frame
# through the running ListenerService is not directly possible; instead we
# use the am-broadcast route on the ListenerService which IS allowed for
# the test harness (tests dir invokes broadcasts via the app's own component).
# Fall through to the UI-driven test if am-broadcast is blocked.
adb -s "$PHONE" shell am start-service \
  -n com.repository.listener/.service.ListenerService \
  -a com.repository.listener.RC_USER_MSG \
  --es rc_session_id "$FRESH_SID" --es rc_data "race-probe" >/dev/null 2>&1

echo "[tool] waiting 18s for expiry (TTL=15s)..."
sleep 18

echo "=== orchestrator log for $FRESH_SID ==="
grep -E "$FRESH_SID|Queued|Replaying|expired" "$LOG" | tail -20

echo
echo "[tool] scenario B: queue-then-drain (simulated by live UI send)"
bash "$(dirname "$0")/test_rc_hang.sh" 2>&1 | tail -25
