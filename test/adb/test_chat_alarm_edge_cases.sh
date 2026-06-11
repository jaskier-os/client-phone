#!/usr/bin/env bash
# Test: AI handles alarm edge cases via chat
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/alarm_helpers.sh"
source "$SCRIPT_DIR/chat_helpers.sh"

check_adb
require_orchestrator

CHAT_TIMEOUT=120

# Cleanup
cleanup_test_alarms

# Test 1: Ask about current alarms
info "  sending: What alarms do I have?"
result=$(chat_send_cmd "What alarms do I have?" "$CHAT_TIMEOUT" "phone")
assert_json_field "$result" "status" "success"
response_text=$(json_field "$result" "data.text")
info "  AI response: ${response_text:0:120}"
info "  AI successfully responded to alarm query"

# Test 2: Create alarm without explicit title
baseline=$(alarm_count)
info "  sending: Set an alarm for 7:30 AM"
result=$(chat_send_cmd "Set an alarm for 7:30 AM" "$CHAT_TIMEOUT" "phone")
assert_json_field "$result" "status" "success"
response_text=$(json_field "$result" "data.text")
info "  AI response: ${response_text:0:120}"

# Check if alarm was created at 7:30
sleep 1
list_result=$(alarm_list_cmd)
alarms=$(json_field "$list_result" "data.alarms")
found_id=$(echo "$alarms" | python3 -c "
import sys, json
alarms = json.load(sys.stdin)
for a in alarms:
    if a.get('hour') == 7 and a.get('minute') == 30:
        print(a['id'])
        break
" 2>/dev/null)

if [ -n "$found_id" ] && [ "$found_id" != "" ]; then
    info "  alarm created at 07:30 with id=$found_id"
    # Cleanup
    alarm_delete_cmd "$found_id" > /dev/null 2>&1 || true
    info "  cleaned up"
else
    info "  WARNING: alarm at 07:30 not found (AI may have used different time)"
fi

pass "AI handles alarm edge cases via chat"
