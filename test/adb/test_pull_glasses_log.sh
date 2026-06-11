#!/usr/bin/env bash
# Test: pull_glasses_log command is accepted and returns a status field.
# Both success and graceful error are passing (glasses may not be reachable).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

echo "=== Test: pull_glasses_log command ==="

check_adb

COMMAND_ID="test_pull_log_$(date +%s)"
info "Sending pull_glasses_log command (id=$COMMAND_ID)..."
send_command "pull_glasses_log" "$COMMAND_ID" > /dev/null

info "Polling for result (90s timeout -- P2P setup takes time)..."
result=$(poll_result "$COMMAND_ID" 90)

info "Result: $result"
assert_json_field_present "$result" "status"
assert_json_field "$result" "type" "pull_glasses_log"
assert_json_field "$result" "command_id" "$COMMAND_ID"

status=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null) || true

if [ "$status" = "success" ] || [ "$status" = "error" ]; then
    pass "pull_glasses_log command accepted (status=$status)"
else
    fail "Unexpected status: $status"
fi
