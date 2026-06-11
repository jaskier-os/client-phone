#!/usr/bin/env bash
# Test: chat_status command returns service state
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/chat_helpers.sh"

echo "=== Test: chat_status ==="
check_adb

result=$(chat_status_cmd)
assert_json_field "$result" "status" "success"
assert_json_field_present "$result" "data"

# Verify expected fields are present
for field in orchestrator_connected glasses_connected service_state glasses_audio_state pending_chat_commands; do
    val=$(json_field "$result" "data.$field")
    if [ -z "$val" ]; then
        fail "data.$field is missing"
    fi
    info "  data.$field = $val"
done

pass "chat_status returns all expected fields"
