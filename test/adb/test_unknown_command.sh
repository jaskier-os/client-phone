#!/usr/bin/env bash
# Test: Unknown command type returns error
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

echo "=== Test: Unknown command returns error ==="

check_adb

COMMAND_ID="test_unknown_$(date +%s)"
info "Sending unknown command type 'nonexistent_command' (id=$COMMAND_ID)..."
send_command "nonexistent_command" "$COMMAND_ID" > /dev/null

info "Polling for result..."
result=$(poll_result "$COMMAND_ID" 10)

info "Result: $result"
assert_json_field "$result" "status" "error"
assert_json_field "$result" "command_id" "$COMMAND_ID"
assert_json_field_present "$result" "error"

pass "unknown command returned error with message"
