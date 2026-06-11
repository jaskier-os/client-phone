#!/usr/bin/env bash
# Test: Per-sender cap of 3 -- oldest notifications from same sender are evicted.
# No glasses required (tests queue logic only, uses timeout fallback).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

echo "=== Test: Per-sender notification cap ==="

check_adb
clear_notif_queue
LOG_BASELINE=$(get_log_baseline)

# 1. Send 5 notifications rapidly from same sender
info "Sending 5 notifications from SpammySender..."
for i in 1 2 3 4 5; do
    CMD_ID="notif_cap_${i}_$(date +%s%N | cut -c1-13)"
    send_command "test_notif" "$CMD_ID" "{\"sender\":\"SpammySender\",\"text\":\"Spam message $i\",\"chat\":\"\"}" > /dev/null
    sleep 0.1
done
info "All 5 sent"

# 2. Check queue status
sleep 2
STATUS_ID="notif_capstat_$(date +%s%N | cut -c1-13)"
send_command "notif_queue_status" "$STATUS_ID" > /dev/null
result=$(poll_result "$STATUS_ID" 5)
assert_json_field "$result" "status" "success"

queue_size=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['queue_size'])")
info "Queue size: $queue_size (should be at most 2, since 1 processing + 2 queued = cap of 3)"
[ "$queue_size" -le 2 ] || fail "Queue should have at most 2 (cap=3 including current), got $queue_size"

# 3. Verify eviction in logs (scoped to this test run)
logs=$(get_logs_since "$LOG_BASELINE")
evict_count=$(echo "$logs" | grep -c "NotifQ.*Evicted" || true)
info "Eviction count: $evict_count"
[ "$evict_count" -ge 2 ] || fail "Expected 2+ evictions for 5 msgs with cap=3, got $evict_count"

pass "Per-sender cap enforced ($evict_count evictions)"
