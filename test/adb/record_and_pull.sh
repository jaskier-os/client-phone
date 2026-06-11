#!/usr/bin/env bash
# Record video on glasses in various configurations and pull to PC.
# Usage:
#   ./record_and_pull.sh                    # Single 5s recording
#   ./record_and_pull.sh <duration>         # Single recording with custom duration
#   ./record_and_pull.sh <duration> <count> # Multiple recordings

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

DURATION=${1:-5}
COUNT=${2:-1}
OUTPUT_DIR="${SCRIPT_DIR}/pulled_recordings"

echo "=== Record & Pull Pipeline ==="
check_adb
mkdir -p "$OUTPUT_DIR"

# Step 1: Check glasses connection
info "Checking glasses connection..."
STATUS_ID="status_$(date +%s)"
send_command "status" "$STATUS_ID" > /dev/null
status_result=$(poll_result "$STATUS_ID" 10)
bt_state=$(echo "$status_result" | python3 -c "import sys,json; d=json.load(sys.stdin).get('data',{}); print(d.get('bt_connected',''))" 2>/dev/null) || true
if [ "$bt_state" != "True" ] && [ "$bt_state" != "true" ]; then
    echo "WARNING: Glasses may not be connected (bt=$bt_state)"
    echo "Result: $status_result"
fi
info "Status OK"

# Step 2: List current storage (before recording)
info "Listing glasses storage (before)..."
LIST_ID="list_before_$(date +%s)"
send_command "list_storage" "$LIST_ID" > /dev/null
list_result=$(poll_result "$LIST_ID" 30) || true
info "Storage: $(echo "$list_result" | python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin).get('data',{}), indent=2)[:500])" 2>/dev/null || echo "$list_result")"

# Step 3: Record
for i in $(seq 1 "$COUNT"); do
    echo ""
    info "=== Recording $i/$COUNT (${DURATION}s) ==="

    REC_ID="rec_${i}_$(date +%s)"
    send_command "record_video" "$REC_ID" "{\"duration\": $DURATION}" > /dev/null

    TIMEOUT=$((DURATION + 60))
    info "Waiting for recording to complete (up to ${TIMEOUT}s)..."
    rec_result=$(poll_result "$REC_ID" "$TIMEOUT")

    rec_status=$(echo "$rec_result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null) || true
    if [ "$rec_status" = "success" ]; then
        info "Recording $i completed successfully"
        info "Result: $(echo "$rec_result" | python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin).get('data',{}))[:200])" 2>/dev/null || echo "$rec_result")"
    else
        echo "WARNING: Recording $i may have failed: $rec_result"
    fi

    # Small delay between recordings
    if [ "$i" -lt "$COUNT" ]; then
        sleep 2
    fi
done

# Step 4: List storage after recording
echo ""
info "Listing glasses storage (after)..."
LIST_ID="list_after_$(date +%s)"
send_command "list_storage" "$LIST_ID" > /dev/null
list_result=$(poll_result "$LIST_ID" 30) || true
info "Storage: $(echo "$list_result" | python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin).get('data',{}), indent=2)[:1000])" 2>/dev/null || echo "$list_result")"

# Step 5: Sync files from glasses to phone
echo ""
info "Syncing files from glasses to phone via WiFi P2P..."
SYNC_ID="sync_$(date +%s)"
send_command "sync_files" "$SYNC_ID" > /dev/null
info "Waiting for sync (up to 120s)..."
sync_result=$(poll_result "$SYNC_ID" 120) || true
sync_status=$(echo "$sync_result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null) || true
info "Sync status: $sync_status"
info "Sync result: $(echo "$sync_result" | python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin).get('data',{}), indent=2)[:500])" 2>/dev/null || echo "$sync_result")"

# Step 6: List synced files on phone and pull
echo ""
info "Listing synced files on phone..."
# Check common locations
for dir in "cache" "files/synced" "files"; do
    info "  Checking $dir/..."
    adb shell run-as "$APP_ID" ls -la "$dir/" 2>/dev/null | head -20 || true
done

# Pull any video files found
info "Searching for video files on phone..."
video_files=$(adb shell run-as "$APP_ID" find cache files -name "*.mp4" -o -name "*.3gp" -o -name "*.mkv" 2>/dev/null || true)

if [ -n "$video_files" ]; then
    echo "$video_files" | while IFS= read -r vfile; do
        [ -z "$vfile" ] && continue
        local_name="$(basename "$vfile")"
        info "Pulling: $vfile -> $OUTPUT_DIR/$local_name"
        adb exec-out run-as "$APP_ID" cat "$vfile" > "$OUTPUT_DIR/$local_name" 2>/dev/null || echo "  Failed to pull $vfile"
    done
    echo ""
    echo "=== Done! Files saved to: $OUTPUT_DIR ==="
    ls -la "$OUTPUT_DIR/"
else
    echo ""
    echo "No video files found on phone after sync."
    echo "The sync may still be in progress, or files are stored in a different location."
    echo "Try listing external storage:"
    adb shell ls -la /sdcard/Download/ 2>/dev/null | tail -10 || true
fi
