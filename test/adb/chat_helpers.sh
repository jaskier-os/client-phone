#!/usr/bin/env bash
# Reusable helpers for ADB chat testing
# Sources test_helpers.sh for base functions (send_command, poll_result, etc.)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

CHAT_POLL_INTERVAL=2
CHAT_SEND_TIMEOUT=600
CHAT_QUERY_TIMEOUT=15

# Send a message to the AI and wait for response
# Usage: chat_send_cmd "message text" [timeout] [device_type]
# Returns: full result JSON on stdout
chat_send_cmd() {
    local text="$1"
    local timeout="${2:-$CHAT_SEND_TIMEOUT}"
    local device_type="${3:-glasses}"
    local cmd_id="chat_$(date +%s%N | cut -c1-13)"

    # Escape double quotes in text for JSON
    local escaped_text
    escaped_text=$(printf '%s' "$text" | sed 's/"/\\"/g')
    local params="{\"text\":\"$escaped_text\",\"device_type\":\"$device_type\"}"

    send_command "chat_send" "$cmd_id" "$params" > /dev/null
    poll_result "$cmd_id" "$timeout"
}

# List conversations
# Usage: chat_list_cmd [limit]
# Returns: full result JSON on stdout
chat_list_cmd() {
    local limit="${1:-20}"
    local cmd_id="chatlist_$(date +%s%N | cut -c1-13)"
    local params="{\"limit\":$limit}"

    send_command "chat_list" "$cmd_id" "$params" > /dev/null
    poll_result "$cmd_id" "$CHAT_QUERY_TIMEOUT"
}

# Get conversation detail
# Usage: chat_get_cmd [conversation_id]
# If no conversation_id, gets the most recent conversation
# Returns: full result JSON on stdout
chat_get_cmd() {
    local conversation_id="${1:-}"
    local cmd_id="chatget_$(date +%s%N | cut -c1-13)"
    local params="{}"

    if [ -n "$conversation_id" ]; then
        params="{\"conversation_id\":\"$conversation_id\"}"
    fi

    send_command "chat_get" "$cmd_id" "$params" > /dev/null
    poll_result "$cmd_id" "$CHAT_QUERY_TIMEOUT"
}

# Create a new conversation
# Usage: chat_new_cmd [device_type]
# Returns: full result JSON on stdout
chat_new_cmd() {
    local device_type="${1:-glasses}"
    local cmd_id="chatnew_$(date +%s%N | cut -c1-13)"
    local params="{\"device_type\":\"$device_type\"}"

    send_command "chat_new" "$cmd_id" "$params" > /dev/null
    poll_result "$cmd_id" "$CHAT_QUERY_TIMEOUT"
}

# Get chat status
# Usage: chat_status_cmd
# Returns: full result JSON on stdout
chat_status_cmd() {
    local cmd_id="chatstatus_$(date +%s%N | cut -c1-13)"

    send_command "chat_status" "$cmd_id" > /dev/null
    poll_result "$cmd_id" 10
}

# Extract a nested JSON field using dot notation
# Usage: json_field <json> <path>
# Example: json_field "$result" "data.text"
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
    else:
        val = ''
        break
if isinstance(val, (dict, list)):
    print(json.dumps(val))
else:
    print(val if val is not None else '')
" 2>/dev/null
}

# Check if orchestrator is connected, skip test if not
# Usage: require_orchestrator
require_orchestrator() {
    local result
    result=$(chat_status_cmd)
    local connected
    connected=$(json_field "$result" "data.orchestrator_connected")
    if [ "$connected" != "True" ]; then
        skip "Orchestrator not connected"
    fi
}
