#!/usr/bin/env bash
# Test: alarm_delete removes an alarm
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/alarm_helpers.sh"

check_adb

# Create
result=$(alarm_create_cmd 23 58 "test_adb_delete")
assert_json_field "$result" "status" "success"
alarm_id=$(json_field "$result" "data.alarm_id")
info "  created alarm_id: $alarm_id"

count_before=$(alarm_count)

# Delete
result=$(alarm_delete_cmd "$alarm_id")
assert_json_field "$result" "status" "success"

deleted=$(json_field "$result" "data.deleted")
if [ "$deleted" != "True" ] && [ "$deleted" != "true" ]; then
    fail "alarm_delete: data.deleted should be true, got '$deleted'"
fi

# Verify removed
count_after=$(alarm_count)
expected=$((count_before - 1))
if [ "$count_after" != "$expected" ]; then
    fail "alarm count should be $expected after delete but got $count_after"
fi

pass "alarm_delete removes alarm from list"
