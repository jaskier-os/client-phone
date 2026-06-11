#!/usr/bin/env bash
# Test: chat_send sends a message and receives AI response
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/chat_helpers.sh"

echo "=== Test: chat_send ==="
check_adb
require_orchestrator

result=$(chat_send_cmd "What is 2+2? Reply with just the number, nothing else." 60)
assert_json_field "$result" "status" "success"

# Verify response fields
text=$(json_field "$result" "data.text")
if [ -z "$text" ]; then
    fail "data.text is empty"
fi
info "  Response text: ${text:0:200}"

request_id=$(json_field "$result" "data.request_id")
if [ -z "$request_id" ]; then
    fail "data.request_id is missing"
fi
info "  request_id: ${request_id:0:8}"

elapsed=$(json_field "$result" "data.elapsed_ms")
info "  elapsed_ms: $elapsed"

tokens=$(json_field "$result" "data.total_tokens")
info "  total_tokens: $tokens"

pass "chat_send received AI response"
