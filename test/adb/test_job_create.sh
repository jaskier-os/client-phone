#!/usr/bin/env bash
# Test: job_create creates a job and it appears in job_list
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/job_helpers.sh"

check_adb
require_orchestrator

JOB_NAME="test_adb_create_$(date +%s)"

# Create
result=$(job_create_cmd "$JOB_NAME" "Say hello world" "2099-12-31T23:59:00.000Z")
assert_json_field "$result" "status" "success"
info "  job created: $JOB_NAME"

# Verify in list
result=$(job_list_cmd)
assert_json_field "$result" "status" "success"

job_id=$(find_job_id "$result" "$JOB_NAME")
if [ -z "$job_id" ] || [ "$job_id" = "" ]; then
    fail "Created job '$JOB_NAME' not found in job_list"
fi
info "  found in list with id: $job_id"

# Cleanup
result=$(job_delete_cmd "$job_id")
assert_json_field "$result" "status" "success"
info "  cleaned up job $job_id"

pass "job_create creates job and appears in list"
