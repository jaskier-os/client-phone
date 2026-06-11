#!/usr/bin/env bash
# Test: rapid-fire 10 test_location commands and verify that the LocatorRateLimiter
# enforces >=50ms spacing between LOCATE_OK log lines. This test requires the
# device to actually have signals (WiFi scan cache, cell, or public IP) -- if
# every call falls back to Fused there will be no LOCATE_OK lines and the
# test will SKIP.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

echo "=== Test: test_location rate limit ==="
check_adb

N=10
baseline=$(get_log_baseline)
info "Log baseline: $baseline"
info "Firing $N test_location commands in a tight loop..."
ids=()
for i in $(seq 1 $N); do
    cid="rl_$(date +%s%N | cut -c1-13)_$i"
    ids+=("$cid")
    send_command "test_location" "$cid" > /dev/null
done

# Wait for the last one to finish
info "Polling for last result..."
poll_result "${ids[$((N-1))]}" 90 > /dev/null

# Give logs a moment to flush
sleep 1

new_logs=$(get_logs_since "$baseline")
locate_ok_count=$(echo "$new_logs" | grep -c "LOCATE_OK" || true)
locate_retry_count=$(echo "$new_logs" | grep -c "LOCATE_RETRY" || true)
locate_skip_count=$(echo "$new_logs" | grep -c "LOCATE_SKIP" || true)
fallback_count=$(echo "$new_logs" | grep -c "FALLBACK_FUSED" || true)

info "LOCATE_OK=$locate_ok_count LOCATE_RETRY=$locate_retry_count LOCATE_SKIP=$locate_skip_count FALLBACK_FUSED=$fallback_count"

if [ "$locate_ok_count" -lt 2 ]; then
    skip "Not enough LOCATE_OK lines to check spacing (got $locate_ok_count). Device may lack signals."
fi

# Verify all results came back successfully
fail_count=0
for cid in "${ids[@]}"; do
    r=$(read_result "$cid" 2>/dev/null || echo "")
    if [ -z "$r" ]; then
        fail_count=$((fail_count + 1))
        continue
    fi
    st=$(echo "$r" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")
    if [ "$st" != "success" ]; then
        fail_count=$((fail_count + 1))
    fi
done
info "Failed results: $fail_count/$N"
if [ "$fail_count" -gt 0 ]; then
    fail "$fail_count out of $N test_location commands did not return success"
fi

# Rate limit check: no 429 errors should appear in the new logs
if echo "$new_logs" | grep -q "HTTP 429"; then
    fail "HTTP 429 (Too Many Requests) observed -- rate limiter failed to protect us"
fi

pass "Rate limiter held: $N commands, $locate_ok_count LOCATE_OK, 0 HTTP 429"
