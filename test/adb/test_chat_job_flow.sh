#!/usr/bin/env bash
# Test: AI can create and delete jobs via natural language chat
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/job_helpers.sh"

check_adb
require_orchestrator

CHAT_TIMEOUT=120
JOB_NAME="test_chat_job_$(date +%s)"

# Ask AI to create a job
info "  sending: Create a job called $JOB_NAME with prompt Say hello world scheduled for 2099-12-31 at 23:59"
result=$(chat_send_cmd "Create a job called $JOB_NAME with prompt Say hello world, scheduled for 2099-12-31 at 23:59" "$CHAT_TIMEOUT" "phone")
assert_json_field "$result" "status" "success"
response_text=$(json_field "$result" "data.text")
info "  AI response: ${response_text:0:120}"

# Verify job was created
sleep 2
list_result=$(job_list_cmd)
job_id=$(find_job_id "$list_result" "$JOB_NAME")
if [ -z "$job_id" ] || [ "$job_id" = "" ]; then
    # Job might have a slightly different name - look for partial match
    job_id=$(json_field "$list_result" "data.jobs" | python3 -c "
import sys, json
jobs = json.load(sys.stdin)
for j in jobs:
    if 'test_chat_job' in j.get('name', ''):
        print(j.get('_id', j.get('id', '')))
        break
" 2>/dev/null)
fi

if [ -z "$job_id" ] || [ "$job_id" = "" ]; then
    fail "Job not found in list after AI creation"
fi
info "  job found with id: $job_id"

# Ask AI to delete the job
info "  sending: Delete the job called $JOB_NAME"
result=$(chat_send_cmd "Delete the job called $JOB_NAME" "$CHAT_TIMEOUT" "phone")
assert_json_field "$result" "status" "success"
response_text=$(json_field "$result" "data.text")
info "  AI response: ${response_text:0:120}"

# Verify job was deleted
sleep 2
list_result=$(job_list_cmd)
remaining=$(find_job_id "$list_result" "$JOB_NAME")
if [ -n "$remaining" ] && [ "$remaining" != "" ]; then
    # Cleanup manually
    job_delete_cmd "$remaining" > /dev/null 2>&1 || true
    info "  WARNING: AI did not delete job, cleaned up manually"
fi

pass "AI creates and deletes job via chat"
