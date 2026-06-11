#!/usr/bin/env bash
# Pull glasses-client.log from glasses via phone WiFi P2P relay.
# The glasses write persistent logs to /sdcard/Download/glasses-client.log
# which is served by the built-in Rokid HTTP server (port 8848).
# This script triggers the phone to download it via WiFi P2P.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

TIMEOUT=90  # P2P setup can take a while

echo "=== Pull Glasses Log ==="

check_adb

COMMAND_ID="pull_log_$(date +%s)"
info "Sending pull_glasses_log command (id=$COMMAND_ID)..."
send_command "pull_glasses_log" "$COMMAND_ID" > /dev/null

info "Waiting for WiFi P2P transfer (up to ${TIMEOUT}s)..."
result=$(poll_result "$COMMAND_ID" "$TIMEOUT")
info "Result: $result"

status=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null) || true

if [ "$status" != "success" ]; then
    echo "FAILED: $result"
    exit 1
fi

OUTPUT_FILE="glasses-client.log"
info "Pulling log file to ./$OUTPUT_FILE ..."
adb exec-out run-as com.repository.listener cat files/adb_results/glasses_debug.log > "$OUTPUT_FILE"

LINES=$(wc -l < "$OUTPUT_FILE")
SIZE=$(wc -c < "$OUTPUT_FILE")
echo "Done: $OUTPUT_FILE ($SIZE bytes, $LINES lines)"
