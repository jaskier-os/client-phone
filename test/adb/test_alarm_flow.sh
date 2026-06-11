#!/usr/bin/env bash
# Test: Full alarm CRUD lifecycle
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/alarm_helpers.sh"

check_adb

# Baseline
baseline=$(alarm_count)
info "  baseline alarm count: $baseline"

# Create first alarm
result=$(alarm_create_cmd 23 57 "test_flow_alarm1")
assert_json_field "$result" "status" "success"
id1=$(json_field "$result" "data.alarm_id")
info "  created alarm1 id=$id1"

# Create second alarm
result=$(alarm_create_cmd 23 56 "test_flow_alarm2")
assert_json_field "$result" "status" "success"
id2=$(json_field "$result" "data.alarm_id")
info "  created alarm2 id=$id2"

# Verify count +2
count=$(alarm_count)
expected=$((baseline + 2))
if [ "$count" != "$expected" ]; then
    alarm_delete_cmd "$id1" > /dev/null 2>&1 || true
    alarm_delete_cmd "$id2" > /dev/null 2>&1 || true
    fail "expected $expected alarms, got $count"
fi
info "  count after 2 creates: $count"

# Delete first
result=$(alarm_delete_cmd "$id1")
assert_json_field "$result" "status" "success"
info "  deleted alarm1"

# Verify count +1
count=$(alarm_count)
expected=$((baseline + 1))
if [ "$count" != "$expected" ]; then
    alarm_delete_cmd "$id2" > /dev/null 2>&1 || true
    fail "expected $expected alarms after first delete, got $count"
fi

# Delete second
result=$(alarm_delete_cmd "$id2")
assert_json_field "$result" "status" "success"
info "  deleted alarm2"

# Verify back to baseline
count=$(alarm_count)
if [ "$count" != "$baseline" ]; then
    fail "expected $baseline alarms after cleanup, got $count"
fi
info "  count back to baseline: $count"

pass "alarm CRUD lifecycle: create 2, verify, delete each, verify baseline"
