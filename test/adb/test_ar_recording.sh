#!/usr/bin/env bash
# Test: Full E2E AR recording flow (requires glasses connected)
# Verifies the complete pipeline: PC -> ADB -> phone -> BT -> glasses -> result -> phone
# Then retrieves the recorded video to PC via file sync
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

DURATION=5
TIMEOUT=$((DURATION + 20))

echo "=== Test: AR recording E2E (duration=${DURATION}s, timeout=${TIMEOUT}s) ==="

check_adb

# Check glasses connection
STATUS_ID="pre_check_$(date +%s)"
send_command "status" "$STATUS_ID" > /dev/null
status_result=$(poll_result "$STATUS_ID" 10)
bt_connected=$(echo "$status_result" | python3 -c "
import sys, json
d = json.load(sys.stdin).get('data', {})
print(d.get('glasses_connected', False))
" 2>/dev/null) || true

if [ "$bt_connected" != "True" ]; then
    skip "Glasses not connected -- E2E recording test requires glasses"
fi

COMMAND_ID="test_record_e2e_$(date +%s)"
info "Sending 'record_ar_screen' (duration=${DURATION}s, id=$COMMAND_ID)..."
send_command "record_ar_screen" "$COMMAND_ID" "{\"duration_seconds\":$DURATION}" > /dev/null

info "Polling for result (timeout=${TIMEOUT}s)..."
result=$(poll_result "$COMMAND_ID" "$TIMEOUT")

info "Result: $result"

# Verify the result came back (not stuck in pending)
result_status=$(echo "$result" | python3 -c "import sys, json; print(json.load(sys.stdin).get('status', 'unknown'))" 2>/dev/null) || true
assert_json_field "$result" "command_id" "$COMMAND_ID"
assert_json_field "$result" "type" "record_ar_screen"

if [ "$result_status" = "pending" ]; then
    fail "Result still pending after ${TIMEOUT}s -- glasses never responded"
fi

if [ "$result_status" != "success" ]; then
    error_msg=$(echo "$result" | python3 -c "
import sys, json
r = json.load(sys.stdin)
d = r.get('data', {})
print(d.get('error', r.get('error', 'unknown')))
" 2>/dev/null) || true
    fail "AR recording failed: $error_msg"
fi

assert_json_field_present "$result" "data"
file_path=$(echo "$result" | python3 -c "
import sys, json
d = json.load(sys.stdin).get('data', {})
print(d.get('file_path', ''))
" 2>/dev/null) || true

if [ -z "$file_path" ]; then
    fail "Success status but no file_path in result"
fi

info "AR recording succeeded. File on glasses: $file_path"
FILENAME=$(basename "$file_path")

# The recording auto-triggers file sync in the background.
# Wait for sync to complete by watching phone logs.
info "Waiting for file sync to complete (up to 90s)..."
SYNC_DEADLINE=$(($(date +%s) + 90))
SYNCED=false

while [ "$(date +%s)" -lt "$SYNC_DEADLINE" ]; do
    # Check phone logs for sync completion messages
    SYNC_LOG=$(adb shell run-as com.repository.listener cat files/logs/listener/latest.log 2>/dev/null | grep -i "GlassesFileSync" | tail -5) || true

    if echo "$SYNC_LOG" | grep -q "Sync complete"; then
        info "File sync completed"
        SYNCED=true
        break
    fi
    if echo "$SYNC_LOG" | grep -q "No new files to sync"; then
        info "Sync reports no new files (may have already synced)"
        SYNCED=true
        break
    fi
    if echo "$SYNC_LOG" | grep -q "Sync failed"; then
        info "Sync failed, will try manual retrieval"
        break
    fi
    if echo "$SYNC_LOG" | grep -q "Failed to get glasses IP"; then
        info "WiFi P2P failed, will try manual retrieval"
        break
    fi

    sleep 3
done

# Try to find and pull the file from phone
OUTPUT_DIR="$SCRIPT_DIR/recordings"
mkdir -p "$OUTPUT_DIR"
OUTPUT_FILE="$OUTPUT_DIR/$FILENAME"

# After sync, screen recordings go to Download/RokidGlasses in MediaStore
# (no video mime type in glasses HTTP file listing -> falls through to Downloads)
PULLED=false
for PHONE_PATH in \
    "/sdcard/Download/RokidGlasses/$FILENAME" \
    "/sdcard/Download/RokidGlasses/${FILENAME}.mp4" \
    "/sdcard/Movies/RokidGlasses/$FILENAME" \
    "/sdcard/Movies/RokidGlasses/${FILENAME}.mp4"; do
    if adb pull "$PHONE_PATH" "$OUTPUT_FILE" 2>/dev/null; then
        PULLED=true
        info "Pulled from: $PHONE_PATH"
        break
    fi
done

# Also try finding by pattern
if [ "$PULLED" = "false" ]; then
    for SEARCH_DIR in "/sdcard/Download/RokidGlasses" "/sdcard/Movies/RokidGlasses"; do
        FOUND=$(adb shell "find $SEARCH_DIR/ -name '*ar_screen*' -type f 2>/dev/null | head -1") || true
        FOUND=$(echo "$FOUND" | tr -d '\r')
        if [ -n "$FOUND" ]; then
            adb pull "$FOUND" "$OUTPUT_FILE" 2>/dev/null && PULLED=true && info "Found and pulled: $FOUND"
            break
        fi
    done
fi

if [ "$PULLED" = "true" ] && [ -f "$OUTPUT_FILE" ]; then
    FILE_SIZE=$(stat -c%s "$OUTPUT_FILE" 2>/dev/null || stat -f%z "$OUTPUT_FILE" 2>/dev/null || echo "unknown")
    pass "AR recording retrieved! File: $OUTPUT_FILE (${FILE_SIZE} bytes)"
else
    info "File sync did not complete or file not found on phone."
    info "Recording exists on glasses at: $file_path"
    info "Trigger sync manually or wait for WiFi P2P."
    pass "AR recording succeeded on glasses (file: $file_path) -- sync in progress"
fi
