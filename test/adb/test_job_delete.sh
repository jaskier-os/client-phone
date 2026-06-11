#!/usr/bin/env bash
# Test: job_delete removes a job from the list
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/job_helpers.sh"

check_adb
require_orchestrator

JOB_NAME="test_adb_delete_$(date +%s)"

# Create
result=$(job_create_cmd "$JOB_NAME" "Test prompt" "2099-12-31T23:59:00.000Z")
assert_json_field "$result" "status" "success"
info "  job created: $JOB_NAME"

# Find its ID
result=$(job_list_cmd)
job_id=$(find_job_id "$result" "$JOB_NAME")
if [ -z "$job_id" ]; then
    fail "Created job not found in list"
fi
info "  found job_id: $job_id"

# Delete
result=$(job_delete_cmd "$job_id")
assert_json_field "$result" "status" "success"
info "  deleted job"

# Verify gone
result=$(job_list_cmd)
remaining_id=$(find_job_id "$result" "$JOB_NAME")
if [ -n "$remaining_id" ] && [ "$remaining_id" != "" ]; then
    fail "Job '$JOB_NAME' still in list after delete"
fi

pass "job_delete removes job from list"
