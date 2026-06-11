#!/usr/bin/env bash
# Test: Single notification flows through the full queue pipeline.
# Glasses required.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

echo "=== Test: Single notification e2e ==="

check_adb
clear_notif_queue
LOG_BASELINE=$(get_log_baseline)

# 1. Send test notification
CMD_ID="notif_single_$(date +%s%N | cut -c1-13)"
info "Sending test notification (id=$CMD_ID)..."
send_command "test_notif" "$CMD_ID" "{\"sender\":\"TestUser\",\"text\":\"Single notif test\",\"chat\":\"\"}" > /dev/null

result=$(poll_result "$CMD_ID" 10)
assert_json_field "$result" "status" "success"
info "Notification queued successfully"

# 2. Wait for TTS + overlay cycle to complete
info "Waiting 25s for notification to complete on glasses..."
sleep 25

# 3. Check queue status -- should be idle
STATUS_ID="notif_status_$(date +%s%N | cut -c1-13)"
send_command "notif_queue_status" "$STATUS_ID" > /dev/null
status_result=$(poll_result "$STATUS_ID" 5)
assert_json_field "$status_result" "status" "success"

queue_size=$(echo "$status_result" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['queue_size'])")
is_processing=$(echo "$status_result" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['is_processing'])")
info "Queue size: $queue_size, processing: $is_processing"
[ "$queue_size" = "0" ] || fail "Queue not empty: $queue_size"
[ "$is_processing" = "False" ] || fail "Still processing"

# 4. Verify pipeline in phone logs (scoped to this test run)
info "Checking phone logs for full pipeline..."
logs=$(get_logs_since "$LOG_BASELINE")
echo "$logs" | grep -q "NotifQ.*Enqueued" || fail "Missing enqueue log"
echo "$logs" | grep -q "NotifQ.*Processing" || fail "Missing processing log"
echo "$logs" | grep -q "NotifQ.*Sending to glasses" || fail "Missing send-to-glasses log"
echo "$logs" | grep -q "Glasses notification done\|NotifQ.*Glasses done" || fail "Missing glasses done log"
echo "$logs" | grep -q "NotifQ.*Queue empty" || fail "Missing idle log"

pass "Single notification completed full pipeline"
