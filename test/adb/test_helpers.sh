#!/usr/bin/env bash
# Shared helpers for ADB command tests

set -euo pipefail

APP_ID="com.repository.listener"
ADB_ACTION="com.repository.listener.ADB_COMMAND"
RESULTS_DIR="files/adb_results"
POLL_INTERVAL=2
DEFAULT_TIMEOUT=30

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}PASS${NC}: $1"; }
fail() { echo -e "${RED}FAIL${NC}: $1"; exit 1; }
skip() { echo -e "${YELLOW}SKIP${NC}: $1"; exit 0; }
info() { echo -e "  $1"; }

# Check ADB connectivity
check_adb() {
    if ! adb get-state >/dev/null 2>&1; then
        fail "No ADB device connected"
    fi
}

# Send an ADB command broadcast to the phone app
# Usage: send_command <type> [command_id] [params_json]
# Note: params_json should use escaped quotes, e.g. {\"key\":\"value\"}
# The command wraps params in single quotes on the device shell to preserve JSON.
send_command() {
    local type="$1"
    local command_id="${2:-cmd_$(date +%s%N | cut -c1-13)}"
    local params="${3:-{}}"

    adb shell "am broadcast \
        -n $APP_ID/.adb.AdbCommandReceiver \
        -a $ADB_ACTION \
        --es type $type \
        --es command_id $command_id \
        --es params '$params'" \
        2>&1

    echo "$command_id"
}

# Read result file for a given command ID
# Usage: read_result <command_id>
read_result() {
    local command_id="$1"
    adb shell run-as "$APP_ID" cat "$RESULTS_DIR/${command_id}.json" 2>/dev/null
}

# Poll result file until status is no longer "pending" or timeout
# Usage: poll_result <command_id> [timeout_seconds]
poll_result() {
    local command_id="$1"
    local timeout="${2:-$DEFAULT_TIMEOUT}"
    local elapsed=0

    while [ "$elapsed" -lt "$timeout" ]; do
        local result
        result=$(read_result "$command_id" 2>/dev/null) || true

        if [ -n "$result" ]; then
            local status
            status=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null) || true
            if [ "$status" != "pending" ] && [ -n "$status" ]; then
                echo "$result"
                return 0
            fi
        fi

        sleep "$POLL_INTERVAL"
        elapsed=$((elapsed + POLL_INTERVAL))
    done

    fail "Timeout after ${timeout}s waiting for result of command $command_id"
}

# Assert a JSON field equals expected value
# Usage: assert_json_field <json_string> <field> <expected>
assert_json_field() {
    local json="$1"
    local field="$2"
    local expected="$3"

    local actual
    actual=$(echo "$json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('$field',''))" 2>/dev/null) || true

    if [ "$actual" = "$expected" ]; then
        info "  $field = '$actual' (expected '$expected')"
    else
        fail "$field = '$actual', expected '$expected'"
    fi
}

# Assert a JSON field is non-empty
# Usage: assert_json_field_present <json_string> <field>
assert_json_field_present() {
    local json="$1"
    local field="$2"

    local actual
    actual=$(echo "$json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('$field',''))" 2>/dev/null) || true

    if [ -n "$actual" ]; then
        info "  $field present: '${actual:0:80}'"
    else
        fail "$field is empty or missing"
    fi
}

# Clean up result files
cleanup_results() {
    adb shell run-as "$APP_ID" rm -rf "$RESULTS_DIR" 2>/dev/null || true
}

# Clear notification queue (for test isolation)
clear_notif_queue() {
    local cid="clear_$(date +%s%N | cut -c1-13)"
    send_command "notif_queue_clear" "$cid" > /dev/null
    sleep 0.5
}

# Get current line count of listener log (for scoping log checks to current test)
# Usage: baseline=$(get_log_baseline)
get_log_baseline() {
    adb shell run-as "$APP_ID" wc -l files/logs/listener/latest.log 2>/dev/null | awk '{print $1}' || echo "0"
}

# Get log lines added since baseline
# Usage: new_logs=$(get_logs_since "$baseline")
get_logs_since() {
    local baseline="$1"
    local skip=$((baseline + 1))
    adb shell run-as "$APP_ID" tail -n "+$skip" files/logs/listener/latest.log 2>/dev/null || echo ""
}
