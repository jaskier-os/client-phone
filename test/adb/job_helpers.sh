#!/usr/bin/env bash
# Reusable helpers for job ADB testing
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/chat_helpers.sh"

JOB_POLL_TIMEOUT=30

job_list_cmd() {
    local cmd_id="joblist_$(date +%s%N | cut -c1-13)"
    send_command "job_list" "$cmd_id" > /dev/null
    poll_result "$cmd_id" "$JOB_POLL_TIMEOUT"
}

job_create_cmd() {
    local name="$1"
    local prompt="$2"
    local scheduled_at="$3"
    local cmd_id="jobcreate_$(date +%s%N | cut -c1-13)"
    local escaped_name=$(printf '%s' "$name" | sed 's/"/\\"/g')
    local escaped_prompt=$(printf '%s' "$prompt" | sed 's/"/\\"/g')
    local params="{\"name\":\"$escaped_name\",\"prompt\":\"$escaped_prompt\",\"scheduled_at\":\"$scheduled_at\"}"
    send_command "job_create" "$cmd_id" "$params" > /dev/null
    poll_result "$cmd_id" "$JOB_POLL_TIMEOUT"
}

job_delete_cmd() {
    local id="$1"
    local cmd_id="jobdelete_$(date +%s%N | cut -c1-13)"
    local params="{\"id\":\"$id\"}"
    send_command "job_delete" "$cmd_id" "$params" > /dev/null
    poll_result "$cmd_id" "$JOB_POLL_TIMEOUT"
}

# Find a job by name in the jobs list and return its _id
# Usage: job_id=$(find_job_id "$job_list_result" "job_name")
find_job_id() {
    local result="$1"
    local name="$2"
    local jobs
    jobs=$(json_field "$result" "data.jobs")
    echo "$jobs" | python3 -c "
import sys, json
jobs = json.load(sys.stdin)
for j in jobs:
    if j.get('name', '') == '$name':
        print(j.get('_id', j.get('id', '')))
        break
" 2>/dev/null
}

job_count() {
    local result
    result=$(job_list_cmd)
    local jobs
    jobs=$(json_field "$result" "data.jobs")
    echo "$jobs" | python3 -c "
import sys, json
jobs = json.load(sys.stdin)
print(len(jobs))
" 2>/dev/null
}
