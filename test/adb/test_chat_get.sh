#!/usr/bin/env bash
# Test: chat_get returns conversation detail with turns
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/chat_helpers.sh"

echo "=== Test: chat_get ==="
check_adb

# First check if there are any conversations
list_result=$(chat_list_cmd 1)
list_status=$(json_field "$list_result" "status")
if [ "$list_status" != "success" ]; then
    skip "Cannot list conversations: $(json_field "$list_result" "error")"
fi

convos=$(json_field "$list_result" "data.conversations")
count=$(echo "$convos" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
if [ "$count" -eq 0 ]; then
    skip "No conversations to test with"
fi

# Get the most recent conversation (no ID = latest)
result=$(chat_get_cmd)
assert_json_field "$result" "status" "success"

conv_id=$(json_field "$result" "data.conversation_id")
turn_count=$(json_field "$result" "data.turn_count")
device_type=$(json_field "$result" "data.device_type")

info "  conversation_id: ${conv_id:0:8}"
info "  turn_count: $turn_count"
info "  device_type: $device_type"

# Verify turns array
turns=$(json_field "$result" "data.turns")
if [ -z "$turns" ]; then
    fail "data.turns is missing"
fi

turns_count=$(echo "$turns" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
info "  turns returned: $turns_count"

pass "chat_get returns conversation detail"
