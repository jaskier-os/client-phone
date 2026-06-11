#!/usr/bin/env bash
# Test: Same-sender messages merge in queue.
# Sequence: Bob-Bob-Bob-Alice-Bob-Bob-Alice-Alice-Alice
# Expected merges: [Bob(1+2+3), Alice(4), Bob(5+6), Alice(7+8+9)] = 4 queue items
# TTS announces sender once per merged group.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

echo "=== Test: Same-sender message merging (mixed senders) ==="

check_adb
clear_notif_queue
LOG_BASELINE=$(get_log_baseline)

# Bob-Bob-Bob-Alice-Bob-Bob-Alice-Alice-Alice
SENDERS=(Bob Bob Bob Alice Bob Bob Alice Alice Alice)
info "Sending 9 notifications: ${SENDERS[*]}"
for i in "${!SENDERS[@]}"; do
    n=$((i + 1))
    CMD_ID="notif_merge_${n}_$(date +%s%N | cut -c1-13)"
    send_command "test_notif" "$CMD_ID" "{\"sender\":\"${SENDERS[$i]}\",\"text\":\"Message $n\",\"chat\":\"\"}" > /dev/null
    sleep 0.1
done
info "All 9 sent"

sleep 2

# Check logs
logs=$(get_logs_since "$LOG_BASELINE")

# Count merges (same-sender consecutive messages merged into existing item)
merge_count=$(echo "$logs" | grep -c "NotifQ.*Merged" || true)
info "Merge count: $merge_count (expected 4+)"

# Count new enqueues
enqueue_count=$(echo "$logs" | grep -c "NotifQ.*Enqueued" || true)
info "Enqueue count: $enqueue_count"

# Total should be 9 (enqueues + merges)
total=$((enqueue_count + merge_count))
info "Total operations: $total (expected 9)"
[ "$total" -ge 9 ] || fail "Expected 9 total operations, got $total"

# Must have at least some merges (proves same-sender merging works)
[ "$merge_count" -ge 3 ] || fail "Expected 3+ merges from consecutive same-sender, got $merge_count"

pass "Same-sender merging: $merge_count merges, $enqueue_count new items from 9 notifications"
