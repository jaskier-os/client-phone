#!/usr/bin/env bash
# Capture RAW sensor frames from glasses at different exposure/boost settings
# and pull them to PC for ML training data.
#
# Usage:
#   ./capture_raw_dataset.sh                     # Default: 6 configs x 3 frames
#   ./capture_raw_dataset.sh <frames_per_config> # Custom frame count
#   ./capture_raw_dataset.sh <frames> <output_dir>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

FRAMES_PER_CONFIG=${1:-3}
OUTPUT_DIR="${2:-$SCRIPT_DIR/raw_dataset}"
PULL_TIMEOUT=90

# Capture configurations: exposure_ms,boost,label
# For "Learning to See in the Dark": pair short-exposure (input) with long-exposure (ground truth)
CONFIGS=(
    "1,100,short_1ms_b100"
    "10,100,short_10ms_b100"
    "50,100,medium_50ms_b100"
    "100,100,medium_100ms_b100"
    "0,100,long_max_b100"
    "0,3199,long_max_b3199"
)

echo "=== RAW Dataset Capture ==="
echo "  Configs: ${#CONFIGS[@]}"
echo "  Frames per config: $FRAMES_PER_CONFIG"
echo "  Output: $OUTPUT_DIR"
echo ""

check_adb
mkdir -p "$OUTPUT_DIR"

# Check glasses connection
STATUS_ID="s_$(date +%s)"
send_command "status" "$STATUS_ID" > /dev/null
status_result=$(poll_result "$STATUS_ID" 10)
bt=$(echo "$status_result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('glasses_connected',''))" 2>/dev/null) || true
if [ "$bt" != "true" ] && [ "$bt" != "True" ]; then
    echo "ERROR: Glasses not connected"
    exit 1
fi
info "Glasses connected"

# Clean glasses storage before starting
info "Cleaning glasses RAW storage..."
CLR_ID="clr_$(date +%s)"
send_command "clear_raw" "$CLR_ID" > /dev/null
poll_result "$CLR_ID" 30 > /dev/null 2>&1 || true
info "Storage cleaned"

for config in "${CONFIGS[@]}"; do
    IFS=',' read -r exp_ms boost label <<< "$config"
    echo ""
    echo "=== Config: $label (exposure=${exp_ms}ms boost=${boost}) ==="

    # Capture frames
    CAP_ID="cap_${label}_$(date +%s)"
    send_command "capture_raw" "$CAP_ID" "{\"num_frames\": $FRAMES_PER_CONFIG, \"exposure_ms\": $exp_ms, \"boost\": $boost}" > /dev/null

    info "Capturing $FRAMES_PER_CONFIG frames..."
    cap_result=$(poll_result "$CAP_ID" 120)
    num_saved=$(echo "$cap_result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('num_saved',0))" 2>/dev/null) || true
    paths=$(echo "$cap_result" | python3 -c "import sys,json; [print(p) for p in json.load(sys.stdin).get('data',{}).get('paths',[])]" 2>/dev/null) || true

    if [ "$num_saved" = "0" ]; then
        echo "  FAILED - no frames saved"
        continue
    fi
    info "Captured $num_saved frames"

    # Pull each file: glasses -> phone -> PC, then clean up
    while IFS= read -r remote_path; do
        [ -z "$remote_path" ] && continue
        filename=$(basename "$remote_path")

        # Glasses -> Phone (WiFi P2P)
        PULL_ID="p_$(date +%s%N | cut -c1-13)"
        send_command "pull_glasses_file" "$PULL_ID" "{\"path\": \"$remote_path\"}" > /dev/null
        pull_result=$(poll_result "$PULL_ID" "$PULL_TIMEOUT") || { echo "  Timeout: $filename"; continue; }
        pull_ok=$(echo "$pull_result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null) || true
        if [ "$pull_ok" != "success" ]; then
            echo "  Pull failed: $filename"
            continue
        fi

        # Phone -> PC
        adb exec-out run-as com.repository.listener cat "files/adb_results/$filename" > "$OUTPUT_DIR/$filename" 2>/dev/null
        size=$(stat -c%s "$OUTPUT_DIR/$filename" 2>/dev/null || echo "0")
        info "  $filename ($((size / 1024 / 1024))MB)"

        # Clean up phone copy
        adb shell run-as com.repository.listener rm -f "files/adb_results/$filename" 2>/dev/null || true
    done <<< "$paths"

    sleep 1
done

# Clean glasses storage after all captures
echo ""
info "Cleaning glasses storage..."
CLR_ID="clr_end_$(date +%s)"
send_command "clear_raw" "$CLR_ID" > /dev/null
poll_result "$CLR_ID" 30 > /dev/null 2>&1 || true

echo ""
echo "=== Dataset Complete ==="
echo "  Output: $OUTPUT_DIR"
ls -lh "$OUTPUT_DIR/" 2>/dev/null | tail -30
echo ""
echo "Total: $(ls "$OUTPUT_DIR/"*.raw "$OUTPUT_DIR/"*.dng 2>/dev/null | wc -l) files, $(du -sh "$OUTPUT_DIR" 2>/dev/null | cut -f1)"
