#!/usr/bin/env bash
# Test: alarm_create creates an alarm and it appears in alarm_list
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/alarm_helpers.sh"

check_adb

# Baseline
initial_count=$(alarm_count)
info "  initial alarm count: $initial_count"

# Create
result=$(alarm_create_cmd 23 59 "test_adb_create")
assert_json_field "$result" "status" "success"

alarm_id=$(json_field "$result" "data.alarm_id")
if [ -z "$alarm_id" ] || [ "$alarm_id" = "" ]; then
    fail "alarm_create: data.alarm_id is missing"
fi
info "  created alarm_id: $alarm_id"

# Verify in list
new_count=$(alarm_count)
expected=$((initial_count + 1))
if [ "$new_count" != "$expected" ]; then
    alarm_delete_cmd "$alarm_id" > /dev/null 2>&1 || true
    fail "alarm count should be $expected but got $new_count"
fi

# Cleanup
alarm_delete_cmd "$alarm_id" > /dev/null 2>&1 || true

pass "alarm_create creates alarm and appears in list"
