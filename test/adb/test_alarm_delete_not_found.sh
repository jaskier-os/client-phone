#!/usr/bin/env bash
# Test: alarm_delete with non-existent id returns error
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/alarm_helpers.sh"

check_adb

result=$(alarm_delete_cmd 99999)
assert_json_field "$result" "status" "error"
info "  non-existent alarm correctly returns error"

pass "alarm_delete returns error for non-existent alarm"
