#!/usr/bin/env bash
# Test: Locator path -> Fused fallback.
#
# Strategy: temporarily install an invalid Locator API key via an ADB command
# (set_locator_key) so the Locator path returns 403 fatal, then verify that
# test_location still succeeds via the Fused fallback with source=fused.
#
# After the test, restore the original key.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

echo "=== Test: Locator fallback to Fused ==="
check_adb

BAD_KEY="invalid-key-for-fallback-test-000000"
# Real key to restore after the test. Supply via env; empty clears the runtime
# override so the app falls back to its build-time default.
RESTORE_KEY="${LOCATOR_API_KEY:-}"

info "Installing invalid Locator API key..."
set_cid="setkey_bad_$(date +%s)"
send_command "set_locator_key" "$set_cid" "{\"key\":\"$BAD_KEY\"}" > /dev/null
poll_result "$set_cid" 10 > /dev/null

baseline=$(get_log_baseline)

info "Calling test_location with bad key..."
cid="fallback_$(date +%s)"
send_command "test_location" "$cid" > /dev/null

result=$(poll_result "$cid" 90)
info "Result: $result"

# Restore original key immediately (before any assertion failure)
info "Restoring original Locator API key..."
restore_cid="setkey_restore_$(date +%s)"
send_command "set_locator_key" "$restore_cid" "{\"key\":\"$RESTORE_KEY\"}" > /dev/null
poll_result "$restore_cid" 10 > /dev/null

# Now validate the fallback result
status=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))")
if [ "$status" != "success" ]; then
    err=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('error',''))")
    fail "Fallback failed: status=$status error=$err"
fi

src=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('source',''))")
info "source=$src"
if [ "$src" != "fused" ]; then
    fail "Expected source=fused after bad key, got '$src'"
fi

# Verify the log shows the Locator path was tried and failed fatally
new_logs=$(get_logs_since "$baseline")
if ! echo "$new_logs" | grep -q "LOCATE_FATAL"; then
    fail "Expected LOCATE_FATAL in logs but not found"
fi
if ! echo "$new_logs" | grep -q "FALLBACK_FUSED"; then
    fail "Expected FALLBACK_FUSED in logs but not found"
fi

pass "Fallback path works: bad Locator key -> LOCATE_FATAL -> FALLBACK_FUSED -> source=fused"
