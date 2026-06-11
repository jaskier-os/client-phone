#!/usr/bin/env bash
# Test: test_location ADB command resolves a location and returns JSON
# Verifies the Locator API primary path OR the Fused fallback path.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

echo "=== Test: test_location ==="

check_adb

COMMAND_ID="test_loc_$(date +%s)"
info "Sending test_location (id=$COMMAND_ID)..."
send_command "test_location" "$COMMAND_ID" > /dev/null

info "Polling for result (timeout 60s)..."
result=$(poll_result "$COMMAND_ID" 60)

info "Result: $result"
status=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))")
if [ "$status" != "success" ]; then
    err=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('error',''))")
    fail "status=$status error=$err"
fi

# Validate JSON fields
lat=$(echo "$result" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('lat',''))")
lng=$(echo "$result" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('lng',''))")
acc=$(echo "$result" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('accuracy',''))")
src=$(echo "$result" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('source',''))")
elapsed=$(echo "$result" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('elapsed_ms',''))")

if [ -z "$lat" ] || [ -z "$lng" ] || [ -z "$acc" ]; then
    fail "Missing lat/lng/accuracy in data: $result"
fi

info "lat=$lat lng=$lng accuracy=${acc}m source=$src elapsed=${elapsed}ms"

if [ "$src" != "locator" ] && [ "$src" != "fused" ]; then
    fail "Unexpected source value: '$src' (expected 'locator' or 'fused')"
fi

# Sanity range check on coordinates
if python3 -c "import sys; lat=$lat; lng=$lng; sys.exit(0 if -90<=lat<=90 and -180<=lng<=180 else 1)"; then
    info "Coordinates within valid range"
else
    fail "Coordinates out of range: lat=$lat lng=$lng"
fi

# Accuracy should be positive and finite
if python3 -c "import sys; a=$acc; sys.exit(0 if 0 < a < 1e7 else 1)"; then
    info "Accuracy within plausible range"
else
    fail "Accuracy out of range: $acc"
fi

pass "test_location returned a valid location (source=$src)"
