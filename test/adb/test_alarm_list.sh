#!/usr/bin/env bash
# Test: alarm_list returns success with count field
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/alarm_helpers.sh"

check_adb

result=$(alarm_list_cmd)
assert_json_field "$result" "status" "success"
assert_json_field "$result" "type" "alarm_list"

count=$(json_field "$result" "data.count")
if [ -z "$count" ]; then
    fail "alarm_list: data.count is missing"
fi
info "  alarm count: $count"

pass "alarm_list returns success with count=$count"
