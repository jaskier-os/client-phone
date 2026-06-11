#!/bin/bash
# Test: audio relay WebRTC connection stability
# Starts audio relay, waits, checks if it stays active.
# Requires: orchestrator + desktop client running.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PACKAGE="com.repository.listener"
RECEIVER="$PACKAGE/.adb.AdbCommandReceiver"
ACTION="$PACKAGE.ADB_COMMAND"
DURATION=${1:-120}  # seconds to monitor (default 2 minutes)
CHECK_INTERVAL=10

send_cmd() {
    local id="$1" type="$2" params="$3"
    adb shell am broadcast -n "$RECEIVER" -a "$ACTION" \
        --es type "$type" --es command_id "$id" --es params "$params" > /dev/null 2>&1
    sleep 1
    adb shell run-as "$PACKAGE" cat "files/adb_results/${id}.json" 2>/dev/null
}

echo "=== Audio Relay Stability Test ==="
echo "Duration: ${DURATION}s, checking every ${CHECK_INTERVAL}s"
echo ""

# Check prerequisites
echo "[1/4] Checking status..."
STATUS=$(send_cmd "ar_pre" "status" "{}")
ORCH=$(echo "$STATUS" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('orchestrator_connected',False))" 2>/dev/null)
if [ "$ORCH" != "True" ]; then
    echo "FAIL: Orchestrator not connected"
    echo "$STATUS" | python3 -m json.tool 2>/dev/null || echo "$STATUS"
    exit 1
fi
echo "  Orchestrator: connected"

# Start audio relay
echo "[2/4] Starting audio relay..."
START_RESULT=$(send_cmd "ar_start" "audio_relay" '{"action":"start"}')
START_STATUS=$(echo "$START_RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
if [ "$START_STATUS" != "success" ]; then
    echo "FAIL: Could not start audio relay"
    echo "$START_RESULT" | python3 -m json.tool 2>/dev/null || echo "$START_RESULT"
    exit 1
fi
echo "  Audio relay start sent"

# Wait for WebRTC to connect
echo "[3/4] Waiting 15s for WebRTC to connect..."
sleep 15

# Check if active
AR_STATUS=$(send_cmd "ar_check0" "audio_relay" '{}')
ACTIVE=$(echo "$AR_STATUS" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('audio_relay_active',False))" 2>/dev/null)
if [ "$ACTIVE" != "True" ]; then
    echo "FAIL: Audio relay not active after 15s"
    echo "$AR_STATUS" | python3 -m json.tool 2>/dev/null || echo "$AR_STATUS"
    # Stop relay
    send_cmd "ar_stop_fail" "audio_relay" '{"action":"stop"}' > /dev/null 2>&1
    exit 1
fi
echo "  Audio relay active"

# Monitor stability
echo "[4/4] Monitoring for ${DURATION}s..."
ELAPSED=0
CHECKS=0
while [ $ELAPSED -lt $DURATION ]; do
    sleep $CHECK_INTERVAL
    ELAPSED=$((ELAPSED + CHECK_INTERVAL))
    CHECKS=$((CHECKS + 1))

    CHECK_RESULT=$(send_cmd "ar_check${CHECKS}" "audio_relay" '{}')
    STILL_ACTIVE=$(echo "$CHECK_RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('audio_relay_active',False))" 2>/dev/null)

    if [ "$STILL_ACTIVE" = "True" ]; then
        echo "  ${ELAPSED}s: active"
    else
        echo "  ${ELAPSED}s: DROPPED"
        echo "FAIL: Audio relay dropped after ${ELAPSED}s"
        # Try to get phone logs around the drop
        adb shell run-as "$PACKAGE" cat files/logs/listener/latest.log 2>/dev/null | grep -i 'webrtc\|ICE\|grace\|audio.*relay\|disconnect\|FAILED' | tail -15
        send_cmd "ar_stop_fail2" "audio_relay" '{"action":"stop"}' > /dev/null 2>&1
        exit 1
    fi
done

echo ""
echo "PASS: Audio relay stayed active for ${DURATION}s ($CHECKS checks)"

# Clean up
send_cmd "ar_stop_pass" "audio_relay" '{"action":"stop"}' > /dev/null 2>&1
echo "Audio relay stopped."
