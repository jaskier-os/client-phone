#!/usr/bin/env bash
# Test: Same-sender messages don't merge across queue drains.
# Batch 1: Bob-Bob-Bob (merges to 1 item), drain, Batch 2: Bob-Bob-Bob (new item, merges to 1).
# Expected: 2 separate Processing logs (one per batch), each with merged text.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

echo "=== Test: Same-sender merge resets after queue drain ==="

check_adb
clear_notif_queue
LOG_BASELINE=$(get_log_baseline)

# Batch 1: Bob-Bob-Bob
info "Batch 1: Sending 3 from Bob..."
for i in 1 2 3; do
    CMD_ID="notif_mreset_b1_${i}_$(date +%s%N | cut -c1-13)"
    send_command "test_notif" "$CMD_ID" "{\"sender\":\"Bob\",\"text\":\"Batch1 msg $i\",\"chat\":\"\"}" > /dev/null
    sleep 0.1
done

# Wait for queue to fully drain
info "Waiting for batch 1 to complete..."
for attempt in $(seq 1 30); do
    sleep 2
    STATUS_ID="drain_${attempt}_$(date +%s%N | cut -c1-13)"
    send_command "notif_queue_status" "$STATUS_ID" > /dev/null
    result=$(poll_result "$STATUS_ID" 5)
    qsize=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['queue_size'])")
    proc=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['is_processing'])")
    if [ "$qsize" = "0" ] && [ "$proc" = "False" ]; then
        info "Batch 1 drained after $((attempt * 2))s"
        break
    fi
    [ "$attempt" -lt 30 ] || fail "Batch 1 did not drain in 60s"
done

# Batch 2: Bob-Bob-Bob
info "Batch 2: Sending 3 from Bob..."
for i in 1 2 3; do
    CMD_ID="notif_mreset_b2_${i}_$(date +%s%N | cut -c1-13)"
    send_command "test_notif" "$CMD_ID" "{\"sender\":\"Bob\",\"text\":\"Batch2 msg $i\",\"chat\":\"\"}" > /dev/null
    sleep 0.1
done

sleep 3

# Check logs across both batches
logs=$(get_logs_since "$LOG_BASELINE")

merge_count=$(echo "$logs" | grep -c "NotifQ.*Merged.*Bob" || true)
enqueue_count=$(echo "$logs" | grep -c "NotifQ.*Enqueued.*Bob" || true)
total=$((enqueue_count + merge_count))
info "Enqueues: $enqueue_count, Merges: $merge_count, Total: $total (expected 6 for 2x3 messages)"

# All 6 messages must be accounted for
[ "$total" -ge 6 ] || fail "Expected 6 total operations for 6 messages, got $total"

# Must have merges in both batches (proves merging works)
[ "$merge_count" -ge 2 ] || fail "Expected 2+ merges (1 per batch), got $merge_count"

# Must have separate enqueues per batch (proves queue drain resets merge target)
[ "$enqueue_count" -ge 4 ] || fail "Expected 4+ enqueues (2 per batch: 1st dequeued immediately, 2nd new), got $enqueue_count"

pass "Same-sender merge resets after drain: $enqueue_count enqueues, $merge_count merges across 2 batches"
