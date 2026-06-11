#!/usr/bin/env bash
# AI Conversation Testing Tool
#
# Usage:
#   ./chat.sh send "Hello, how are you?"     Send as glasses (default)
#   ./chat.sh phone send "Hello"             Send as phone
#   ./chat.sh glasses send "Hello"           Send as glasses (explicit)
#   ./chat.sh list                           List all conversations
#   ./chat.sh get [conversation_id]          Get conversation (latest if no ID)
#   ./chat.sh new                            Start new conversation
#   ./chat.sh status                         Connection and service status
#
# Messages sent via "glasses" appear on the glasses chat UI in real-time.
# Messages sent via "phone" appear on the phone chat UI in real-time.
# Both touch the full orchestrator flow, only bypassing voice input.
#
# Environment:
#   CHAT_TIMEOUT=120   Override response timeout (seconds)
#   CHAT_DEVICE=glasses Override device type (glasses/phone)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/chat_helpers.sh"

TIMEOUT="${CHAT_TIMEOUT:-120}"
DEVICE="${CHAT_DEVICE:-glasses}"

cmd_send() {
    local text="${1:?Usage: chat.sh send \"message text\"}"
    echo "[Sending as $DEVICE: ${text:0:80}]"
    local result
    result=$(chat_send_cmd "$text" "$TIMEOUT" "$DEVICE")

    local status
    status=$(json_field "$result" "status")
    if [ "$status" != "success" ]; then
        local error
        error=$(json_field "$result" "error")
        echo "[Error: $error]"
        return 1
    fi

    local response_text elapsed tokens request_id response_status
    response_text=$(json_field "$result" "data.text")
    elapsed=$(json_field "$result" "data.elapsed_ms")
    tokens=$(json_field "$result" "data.total_tokens")
    request_id=$(json_field "$result" "data.request_id")
    response_status=$(json_field "$result" "data.status")

    local elapsed_s=""
    if [ -n "$elapsed" ] && [ "$elapsed" != "0" ]; then
        elapsed_s=$(python3 -c "print(f'{int($elapsed)/1000:.1f}')" 2>/dev/null || echo "$elapsed")
    fi

    echo ""
    echo "Assistant: $response_text"
    echo ""
    echo "[${elapsed_s}s | ${tokens} tokens | status=$response_status | id=${request_id:0:8}]"

    # Check for tool calls
    local tool_calls
    tool_calls=$(json_field "$result" "data.tool_calls")
    if [ -n "$tool_calls" ] && [ "$tool_calls" != "[]" ] && [ "$tool_calls" != "" ]; then
        echo "[Tools used: $tool_calls]"
    fi
}

cmd_list() {
    local limit="${1:-20}"
    local result
    result=$(chat_list_cmd "$limit")

    local status
    status=$(json_field "$result" "status")
    if [ "$status" != "success" ]; then
        echo "[Error: $(json_field "$result" "error")]"
        return 1
    fi

    local total
    total=$(json_field "$result" "data.total")
    echo "Conversations ($total total):"
    echo ""

    python3 -c "
import sys, json
data = json.load(sys.stdin)
convos = data.get('data', {}).get('conversations', [])
if not convos:
    print('  (none)')
else:
    for i, c in enumerate(convos, 1):
        title = (c.get('title', '') or '(empty)')[:60]
        time = c.get('relative_time', '?')
        turns = c.get('turn_count', 0)
        active = 'active' if c.get('is_active', False) else 'closed'
        cid = c.get('id', '?')[:8]
        device = c.get('device_type', '?')
        print(f'  {i}. [{cid}] {title}')
        print(f'     {time} ago | {turns} turns | {active} | {device}')
" <<< "$result"
}

cmd_get() {
    local conversation_id="${1:-}"
    local result
    result=$(chat_get_cmd "$conversation_id")

    local status
    status=$(json_field "$result" "status")
    if [ "$status" != "success" ]; then
        echo "[Error: $(json_field "$result" "error")]"
        return 1
    fi

    local conv_id turn_count device_type
    conv_id=$(json_field "$result" "data.conversation_id")
    turn_count=$(json_field "$result" "data.turn_count")
    device_type=$(json_field "$result" "data.device_type")

    echo "Conversation: ${conv_id:0:8}... ($turn_count turns, $device_type)"
    echo ""

    python3 -c "
import sys, json
data = json.load(sys.stdin)
turns = data.get('data', {}).get('turns', [])
if not turns:
    print('  (no messages)')
else:
    for turn in turns:
        user = turn.get('user_text', '')
        assistant = turn.get('assistant_text', '')
        if user:
            print(f'  USER: {user}')
        if assistant:
            # Truncate long responses for readability
            if len(assistant) > 500:
                assistant = assistant[:500] + '...'
            print(f'  ASSISTANT: {assistant}')
        print()
" <<< "$result"
}

cmd_new() {
    local result
    result=$(chat_new_cmd "$DEVICE")

    local status
    status=$(json_field "$result" "status")
    if [ "$status" != "success" ]; then
        echo "[Error: $(json_field "$result" "error")]"
        return 1
    fi

    local conv_id
    conv_id=$(json_field "$result" "data.conversation_id")
    echo "[New conversation created: $conv_id]"
}

cmd_status() {
    local result
    result=$(chat_status_cmd)

    local status
    status=$(json_field "$result" "status")
    if [ "$status" != "success" ]; then
        echo "[Error: $(json_field "$result" "error")]"
        return 1
    fi

    echo "Chat Status:"
    echo "  Orchestrator: $(json_field "$result" "data.orchestrator_connected")"
    echo "  Glasses:      $(json_field "$result" "data.glasses_connected")"
    echo "  Service:      $(json_field "$result" "data.service_state")"
    echo "  Audio state:  $(json_field "$result" "data.glasses_audio_state")"
    echo "  Last request: $(json_field "$result" "data.last_request_id")"
    echo "  Pending cmds: $(json_field "$result" "data.pending_chat_commands")"
}

# Parse optional device prefix: ./chat.sh glasses send "Hi" or ./chat.sh phone send "Hi"
case "${1:-help}" in
    glasses|phone)
        DEVICE="$1"
        shift
        ;;
esac

case "${1:-help}" in
    send)   shift; cmd_send "$@" ;;
    list)   shift; cmd_list "$@" ;;
    get)    shift; cmd_get "$@" ;;
    new)    shift; cmd_new "$@" ;;
    status) cmd_status ;;
    help|*)
        echo "AI Conversation Testing Tool"
        echo ""
        echo "Usage:"
        echo "  ./chat.sh send \"message\"         Send as glasses (default)"
        echo "  ./chat.sh phone send \"message\"   Send as phone (visible in phone chat)"
        echo "  ./chat.sh glasses send \"message\" Send as glasses (visible in glasses chat)"
        echo "  ./chat.sh list [limit]            List conversations"
        echo "  ./chat.sh get [id]                Get conversation (latest if no ID)"
        echo "  ./chat.sh new                     Start new conversation"
        echo "  ./chat.sh status                  Connection status"
        echo ""
        echo "Environment:"
        echo "  CHAT_TIMEOUT=120     Response timeout in seconds"
        echo "  CHAT_DEVICE=glasses  Default device type"
        ;;
esac
