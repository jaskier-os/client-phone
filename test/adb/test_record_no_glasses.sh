#!/usr/bin/env bash
# Test: record_ar_screen without glasses connected returns graceful error
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

echo "=== Test: record_ar_screen without glasses ==="

check_adb

# Check if glasses are connected by reading status first
STATUS_ID="pre_check_$(date +%s)"
send_command "status" "$STATUS_ID" > /dev/null
status_result=$(poll_result "$STATUS_ID" 10)
bt_connected=$(echo "$status_result" | python3 -c "
import sys, json
d = json.load(sys.stdin).get('data', {})
print(d.get('glasses_connected', False))
" 2>/dev/null) || true

if [ "$bt_connected" = "True" ]; then
    skip "Glasses are connected -- this test requires no glasses connection"
fi

COMMAND_ID="test_record_nobt_$(date +%s)"
info "Sending 'record_ar_screen' without glasses (id=$COMMAND_ID)..."
send_command "record_ar_screen" "$COMMAND_ID" '{"duration":5}' > /dev/null

info "Polling for result..."
result=$(poll_result "$COMMAND_ID" 10)

info "Result: $result"
assert_json_field "$result" "status" "error"
assert_json_field "$result" "command_id" "$COMMAND_ID"
assert_json_field_present "$result" "error"

pass "record_ar_screen without glasses returned graceful error"
