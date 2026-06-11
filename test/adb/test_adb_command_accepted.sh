#!/usr/bin/env bash
# Test: ADB "status" command is accepted and returns success
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

echo "=== Test: ADB command accepted (status) ==="

check_adb

COMMAND_ID="test_status_$(date +%s)"
info "Sending 'status' command (id=$COMMAND_ID)..."
send_command "status" "$COMMAND_ID" > /dev/null

info "Polling for result..."
result=$(poll_result "$COMMAND_ID" 10)

info "Result: $result"
assert_json_field "$result" "status" "success"
assert_json_field "$result" "type" "status"
assert_json_field "$result" "command_id" "$COMMAND_ID"

pass "status command accepted and returned success"
