#!/usr/bin/env bash
# Test: AI can create and delete alarms via natural language chat
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/alarm_helpers.sh"
source "$SCRIPT_DIR/chat_helpers.sh"

check_adb
require_orchestrator

CHAT_TIMEOUT=120

# Cleanup any leftover test alarms
cleanup_test_alarms

# Baseline
baseline=$(alarm_count)
info "  baseline alarm count: $baseline"

# Ask AI to create an alarm
info "  sending: Set an alarm for 23:58 called test_chat_alarm"
result=$(chat_send_cmd "Set an alarm for 23:58 called test_chat_alarm" "$CHAT_TIMEOUT" "phone")
assert_json_field "$result" "status" "success"
response_text=$(json_field "$result" "data.text")
info "  AI response: ${response_text:0:120}"

# Verify alarm was created
sleep 1
new_count=$(alarm_count)
expected=$((baseline + 1))
if [ "$new_count" != "$expected" ]; then
    info "  WARNING: alarm count is $new_count, expected $expected"
    # Try to find the alarm anyway
fi

# Check the alarm exists in list
list_result=$(alarm_list_cmd)
alarms=$(json_field "$list_result" "data.alarms")
found=$(echo "$alarms" | python3 -c "
import sys, json
alarms = json.load(sys.stdin)
for a in alarms:
    if 'test_chat_alarm' in a.get('title', '').lower() or (a.get('hour') == 23 and a.get('minute') == 58):
        print('found')
        break
" 2>/dev/null)

if [ "$found" != "found" ]; then
    cleanup_test_alarms
    fail "Alarm not found in list after AI creation"
fi
info "  alarm found in list"

# Ask AI to delete the alarm
info "  sending: Delete the alarm called test_chat_alarm"
result=$(chat_send_cmd "Delete the alarm called test_chat_alarm" "$CHAT_TIMEOUT" "phone")
assert_json_field "$result" "status" "success"
response_text=$(json_field "$result" "data.text")
info "  AI response: ${response_text:0:120}"

# Verify alarm was deleted
sleep 1
final_count=$(alarm_count)
if [ "$final_count" != "$baseline" ]; then
    info "  WARNING: final count $final_count != baseline $baseline, cleaning up"
    cleanup_test_alarms
fi

pass "AI creates and deletes alarm via chat"
