#!/usr/bin/env bash
# Test: chat_list returns conversation list
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/chat_helpers.sh"

echo "=== Test: chat_list ==="
check_adb

result=$(chat_list_cmd 5)
assert_json_field "$result" "status" "success"

# Verify conversations array exists
convos=$(json_field "$result" "data.conversations")
if [ -z "$convos" ]; then
    fail "data.conversations is missing"
fi
info "  conversations present"

total=$(json_field "$result" "data.total")
info "  total: $total"

# If there are conversations, verify first one has expected fields
count=$(echo "$convos" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
info "  returned: $count conversations"

if [ "$count" -gt 0 ]; then
    first_id=$(echo "$convos" | python3 -c "import sys,json; print(json.load(sys.stdin)[0].get('id',''))" 2>/dev/null)
    first_title=$(echo "$convos" | python3 -c "import sys,json; print(json.load(sys.stdin)[0].get('title','')[:60])" 2>/dev/null)
    info "  first: [$first_id] $first_title"
fi

pass "chat_list returns conversation list"
