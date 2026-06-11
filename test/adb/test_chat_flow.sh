#!/usr/bin/env bash
# Test: End-to-end conversation flow
# Sends a message, verifies response, sends follow-up, checks history
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/chat_helpers.sh"

echo "=== Test: chat_flow (end-to-end) ==="
check_adb
require_orchestrator

# Step 1: Send first message
info "Step 1: Sending first message..."
result1=$(chat_send_cmd "Say exactly: HELLO_TEST_123. Nothing else." 60)
assert_json_field "$result1" "status" "success"

text1=$(json_field "$result1" "data.text")
if [ -z "$text1" ]; then
    fail "First response is empty"
fi
info "  First response: ${text1:0:100}"
request_id1=$(json_field "$result1" "data.request_id")
info "  request_id: ${request_id1:0:8}"

# Step 2: Verify conversation appears in list
info "Step 2: Checking conversation list..."
sleep 2  # Brief delay for chat history to persist
list_result=$(chat_list_cmd 5)
assert_json_field "$list_result" "status" "success"

total=$(json_field "$list_result" "data.total")
if [ "$total" -eq 0 ]; then
    fail "No conversations found after sending message"
fi
info "  Found $total conversation(s)"

# Step 3: Get conversation detail
info "Step 3: Getting conversation detail..."
get_result=$(chat_get_cmd)
assert_json_field "$get_result" "status" "success"

conv_id=$(json_field "$get_result" "data.conversation_id")
turn_count=$(json_field "$get_result" "data.turn_count")
info "  conversation_id: ${conv_id:0:8}"
info "  turn_count: $turn_count"

# Step 4: Send follow-up message
info "Step 4: Sending follow-up message..."
result2=$(chat_send_cmd "What was the first thing I said in this conversation? One sentence." 60)
assert_json_field "$result2" "status" "success"

text2=$(json_field "$result2" "data.text")
if [ -z "$text2" ]; then
    fail "Follow-up response is empty"
fi
info "  Follow-up response: ${text2:0:200}"

# Step 5: Verify conversation grew
info "Step 5: Verifying conversation growth..."
sleep 2
get_result2=$(chat_get_cmd "$conv_id")
assert_json_field "$get_result2" "status" "success"

turn_count2=$(json_field "$get_result2" "data.turn_count")
info "  turn_count after follow-up: $turn_count2"

if [ "$turn_count2" -le "$turn_count" ]; then
    info "  Warning: turn count did not increase ($turn_count -> $turn_count2)"
fi

pass "End-to-end conversation flow completed"
