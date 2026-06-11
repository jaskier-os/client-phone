#!/usr/bin/env bash
# Test: alarm_create with invalid params returns error
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/alarm_helpers.sh"

check_adb

# Invalid hour (25)
cmd_id="alarm_inv1_$(date +%s%N | cut -c1-13)"
send_command "alarm_create" "$cmd_id" '{"hour":25,"minute":0}' > /dev/null
result=$(poll_result "$cmd_id" 10)
assert_json_field "$result" "status" "error"
info "  hour=25 correctly rejected"

# Invalid minute (60)
cmd_id="alarm_inv2_$(date +%s%N | cut -c1-13)"
send_command "alarm_create" "$cmd_id" '{"hour":8,"minute":60}' > /dev/null
result=$(poll_result "$cmd_id" 10)
assert_json_field "$result" "status" "error"
info "  minute=60 correctly rejected"

# Negative hour
cmd_id="alarm_inv3_$(date +%s%N | cut -c1-13)"
send_command "alarm_create" "$cmd_id" '{"hour":-1,"minute":30}' > /dev/null
result=$(poll_result "$cmd_id" 10)
assert_json_field "$result" "status" "error"
info "  hour=-1 correctly rejected"

pass "alarm_create rejects invalid time params"
