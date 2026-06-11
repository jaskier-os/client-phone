#!/usr/bin/env bash
# Reusable helpers for alarm ADB testing
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

ALARM_POLL_TIMEOUT=10

alarm_list_cmd() {
    local cmd_id="alarmlist_$(date +%s%N | cut -c1-13)"
    send_command "alarm_list" "$cmd_id" > /dev/null
    poll_result "$cmd_id" "$ALARM_POLL_TIMEOUT"
}

alarm_create_cmd() {
    local hour="$1"
    local minute="$2"
    local title="${3:-}"
    local cmd_id="alarmcreate_$(date +%s%N | cut -c1-13)"
    local params="{\"hour\":$hour,\"minute\":$minute,\"title\":\"$title\"}"
    send_command "alarm_create" "$cmd_id" "$params" > /dev/null
    poll_result "$cmd_id" "$ALARM_POLL_TIMEOUT"
}

alarm_delete_cmd() {
    local id="$1"
    local cmd_id="alarmdelete_$(date +%s%N | cut -c1-13)"
    local params="{\"id\":$id}"
    send_command "alarm_delete" "$cmd_id" "$params" > /dev/null
    poll_result "$cmd_id" "$ALARM_POLL_TIMEOUT"
}

alarm_delete_by_time_cmd() {
    local hour="$1"
    local minute="$2"
    local cmd_id="alarmdel_$(date +%s%N | cut -c1-13)"
    local params="{\"hour\":$hour,\"minute\":$minute}"
    send_command "alarm_delete" "$cmd_id" "$params" > /dev/null
    poll_result "$cmd_id" "$ALARM_POLL_TIMEOUT"
}

alarm_delete_by_title_cmd() {
    local title="$1"
    local cmd_id="alarmdel_$(date +%s%N | cut -c1-13)"
    local escaped_title=$(printf '%s' "$title" | sed 's/"/\\"/g')
    local params="{\"title\":\"$escaped_title\"}"
    send_command "alarm_delete" "$cmd_id" "$params" > /dev/null
    poll_result "$cmd_id" "$ALARM_POLL_TIMEOUT"
}

# Extract nested JSON field using dot notation
json_field() {
    local json="$1"
    local path="$2"
    echo "$json" | python3 -c "
import sys, json
data = json.load(sys.stdin)
keys = '$path'.split('.')
val = data
for k in keys:
    if isinstance(val, dict):
        val = val.get(k, '')
    elif isinstance(val, list):
        try:
            val = val[int(k)]
        except (ValueError, IndexError):
            val = ''
            break
    else:
        val = ''
        break
if isinstance(val, (dict, list)):
    print(json.dumps(val))
else:
    print(val if val is not None else '')
" 2>/dev/null
}

alarm_count() {
    local result
    result=$(alarm_list_cmd)
    json_field "$result" "data.count"
}

# Clean up test alarms (those with title starting with "test_")
cleanup_test_alarms() {
    local result
    result=$(alarm_list_cmd)
    local alarms
    alarms=$(json_field "$result" "data.alarms")
    if [ -z "$alarms" ] || [ "$alarms" = "" ]; then
        return
    fi
    # Extract IDs of test alarms and delete them
    echo "$alarms" | python3 -c "
import sys, json
alarms = json.load(sys.stdin)
for a in alarms:
    if a.get('title', '').startswith('test_'):
        print(a['id'])
" 2>/dev/null | while read -r aid; do
        alarm_delete_cmd "$aid" > /dev/null 2>&1 || true
    done
}
