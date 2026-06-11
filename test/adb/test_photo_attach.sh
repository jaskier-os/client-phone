#!/usr/bin/env bash
# Test: auto-attach DCIM photo from glasses to AI prompt
# Requires: glasses connected via BT, orchestrator running, DCIM photo on glasses
#
# Flow:
# 1. Sends chat_send with attach_photo=true
# 2. Phone requests latest DCIM photo from glasses via BT
# 3. Glasses read DCIM, send chunked base64 to phone
# 4. Phone attaches photo to orchestrator request
# 5. AI responds describing the photo

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

TEXT="${1:-What do you see on this photo?}"
TIMEOUT="${CHAT_TIMEOUT:-120}"

echo "=== Test: Photo Auto-Attach ==="
check_adb

CMD_ID="photo_$(date +%s)"
info "Sending chat_send with attach_photo=true (text='$TEXT')"
info "This will request the latest DCIM photo from glasses..."

send_command "chat_send" "$CMD_ID" "{\"text\":\"$TEXT\",\"device_type\":\"glasses\",\"attach_photo\":true}" > /dev/null

info "Waiting for response (timeout=${TIMEOUT}s, includes BT photo transfer)..."
RESULT=$(poll_result "$CMD_ID" "$TIMEOUT")

if [ -z "$RESULT" ]; then
    fail "Timeout waiting for response"
fi

STATUS=$(echo "$RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")
RESPONSE_TEXT=$(echo "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin).get('data',{}); print(d.get('text','')[:200])" 2>/dev/null || echo "")

if [ "$STATUS" = "success" ]; then
    pass "AI responded with photo context"
    info "Response: $RESPONSE_TEXT"
elif [ "$STATUS" = "error" ]; then
    ERROR=$(echo "$RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('error','unknown'))" 2>/dev/null || echo "unknown")
    fail "Error: $ERROR"
else
    fail "Unexpected status: $STATUS"
fi
