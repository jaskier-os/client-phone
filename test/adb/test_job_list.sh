#!/usr/bin/env bash
# Test: job_list returns success with jobs field
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/job_helpers.sh"

check_adb
require_orchestrator

result=$(job_list_cmd)
assert_json_field "$result" "status" "success"
assert_json_field "$result" "type" "job_list"
assert_json_field_present "$result" "data"

info "  job_list returned successfully"

pass "job_list returns success"
