#!/usr/bin/env bash
# Test: Different senders don't interfere -- no cross-sender eviction.
# No glasses required. Verifies that notifications from different senders
# are all processed without eviction (unlike same-sender which gets capped).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

echo "=== Test: Multi-sender notifications ==="

check_adb
clear_notif_queue
LOG_BASELINE=$(get_log_baseline)

# 1. Send 3 from Alice and 3 from Bob interleaved
info "Sending 3 from Alice + 3 from Bob..."
for i in 1 2 3; do
    CMD_ID="notif_alice_${i}_$(date +%s%N | cut -c1-13)"
    send_command "test_notif" "$CMD_ID" "{\"sender\":\"Alice\",\"text\":\"Alice msg $i\",\"chat\":\"\"}" > /dev/null
    CMD_ID="notif_bob_${i}_$(date +%s%N | cut -c1-13)"
    send_command "test_notif" "$CMD_ID" "{\"sender\":\"Bob\",\"text\":\"Bob msg $i\",\"chat\":\"\"}" > /dev/null
    sleep 0.1
done
info "All 6 sent"

# 2. Wait for broadcasts to be processed
sleep 2

# 3. Verify all 6 were enqueued (scoped to this test run)
logs=$(get_logs_since "$LOG_BASELINE")
enqueue_count=$(echo "$logs" | grep -c "NotifQ.*Enqueued" || true)
info "Enqueued count: $enqueue_count (expected 6)"
[ "$enqueue_count" -ge 6 ] || fail "Expected 6 enqueued notifications, got $enqueue_count"

# 4. Verify no evictions across different senders (core assertion)
evict_count=$(echo "$logs" | grep -c "NotifQ.*Evicted" || true)
info "Eviction count: $evict_count (should be 0)"
[ "$evict_count" -eq 0 ] || fail "Should be no evictions across different senders, got $evict_count"

# 5. Verify at least 1 is processing (queue started draining)
proc_count=$(echo "$logs" | grep -c "NotifQ.*Processing" || true)
info "Processed count: $proc_count (at least 1 expected)"
[ "$proc_count" -ge 1 ] || fail "Expected at least 1 processing, got $proc_count"

pass "Multi-sender: all 6 notifications enqueued, no cross-eviction"
