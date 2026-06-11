#!/usr/bin/env bash
# Test: Multiple notifications are queued and processed sequentially.
# Glasses required.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

echo "=== Test: Notification queue sequential processing ==="

check_adb
clear_notif_queue
LOG_BASELINE=$(get_log_baseline)

# 1. Send 3 notifications in rapid succession
info "Sending 3 notifications rapidly..."
for i in 1 2 3; do
    CMD_ID="notif_seq_${i}_$(date +%s%N | cut -c1-13)"
    send_command "test_notif" "$CMD_ID" "{\"sender\":\"SeqTest\",\"text\":\"Message $i of 3\",\"chat\":\"\"}" > /dev/null
    sleep 0.2
done
info "All 3 sent"

# 2. Check queue status -- should have items queued
sleep 1
STATUS_ID="notif_qstat_$(date +%s%N | cut -c1-13)"
send_command "notif_queue_status" "$STATUS_ID" > /dev/null
result=$(poll_result "$STATUS_ID" 5)
assert_json_field "$result" "status" "success"

queue_size=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['queue_size'])")
is_processing=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['is_processing'])")
info "Mid-flight: queue_size=$queue_size, processing=$is_processing"
[ "$is_processing" = "True" ] || fail "Should be processing"
[ "$queue_size" -ge 1 ] || fail "Queue should have items (got $queue_size)"

# 3. Wait for all to complete (~25s per notification with glasses timeout)
info "Waiting 80s for all 3 notifications to complete..."
sleep 80

# 4. Verify queue is now empty
STATUS_FINAL="notif_final_$(date +%s%N | cut -c1-13)"
send_command "notif_queue_status" "$STATUS_FINAL" > /dev/null
result=$(poll_result "$STATUS_FINAL" 5)
queue_size=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['queue_size'])")
is_processing=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['is_processing'])")
info "Final: queue_size=$queue_size, processing=$is_processing"
[ "$queue_size" = "0" ] || fail "Queue not empty after processing all: $queue_size"

# 5. Verify sequential processing in logs (scoped to this test run)
info "Checking logs for 3x processing + 3x done..."
logs=$(get_logs_since "$LOG_BASELINE")
proc_count=$(echo "$logs" | grep -c "NotifQ.*Processing" || true)
done_count=$(echo "$logs" | grep -c "Glasses notification done\|NotifQ.*Glasses done:" || true)
info "Processing logs: $proc_count, done signals: $done_count"
[ "$proc_count" -ge 3 ] || fail "Expected 3+ Processing logs, got $proc_count"
[ "$done_count" -ge 3 ] || fail "Expected 3+ done signals, got $done_count"

pass "3 notifications processed sequentially"
