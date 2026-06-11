#!/usr/bin/env bash
# Interactive per-scene capture for "Learning to See in the Dark" dataset.
#
# Following Chen et al., CVPR 2018:
#   - Short exposure inputs: 33ms (1/30s) and 100ms (1/10s)
#   - Long exposure ground truth: 30 x 313ms frames, SUMMED to compose ~9.4s exposure
#   - Amplification ratios: 33ms->9.4s = x285 (paper x300), 100ms->9.4s = x94 (paper x100)
#
# Ground truth is composed by summing many short-max-exposure frames because the sensor
# cannot do exposures longer than 313ms. This is equivalent to accumulating photons over
# a longer integration time, matching the paper's 10-30s single-exposure GT approach.
#
# Usage:
#   ./capture_scene.sh              # Interactive mode, auto-detects next scene number
#   ./capture_scene.sh 5            # Start at scene 5
#   ./capture_scene.sh 1 /tmp/out   # Scene 1, custom output directory

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

AI_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
DATASET_DIR="${2:-$AI_ROOT/clients/glasses/nightvision_dataset}"
GENERATE_SCRIPT="$DATASET_DIR/generate_reference.py"

PULL_TIMEOUT=120
CAPTURE_TIMEOUT=180
BOOST=100

# Capture configs: exposure_ms,num_frames,subdir_name
# Paper: input at 1/30s and 1/10s, GT at 10-30s composed from 313ms frames
CONFIGS=(
    "33,10,short_33ms"
    "100,10,short_100ms"
)
GT_EXPOSURE_MS=313
GT_FRAMES_PER_BATCH=10
GT_BATCHES=3
GT_TOTAL=$((GT_FRAMES_PER_BATCH * GT_BATCHES))
GT_SUBDIR="long_313ms"
GT_COMPOSITE_MS=$((GT_TOTAL * GT_EXPOSURE_MS))

# --- Functions ---

capture_config() {
    local scene_dir="$1"
    local exp_ms="$2"
    local num_frames="$3"
    local subdir="$4"
    local frame_offset="${5:-0}"
    local out_dir="$scene_dir/$subdir"
    mkdir -p "$out_dir"

    echo ""
    echo "--- Capturing $num_frames frames at ${exp_ms}ms (offset=$frame_offset) ---"

    CAP_ID="cap_${exp_ms}ms_$(date +%s)"
    send_command "capture_raw" "$CAP_ID" "{\"num_frames\": $num_frames, \"exposure_ms\": $exp_ms, \"boost\": $BOOST}" > /dev/null

    cap_result=$(poll_result "$CAP_ID" "$CAPTURE_TIMEOUT")
    num_saved=$(echo "$cap_result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('num_saved',0))" 2>/dev/null) || true
    paths=$(echo "$cap_result" | python3 -c "import sys,json; [print(p) for p in json.load(sys.stdin).get('data',{}).get('paths',[])]" 2>/dev/null) || true

    if [ "$num_saved" = "0" ]; then
        echo "  FAILED - no frames captured"
        return 1
    fi
    info "Captured $num_saved frames"

    # Use mapfile to avoid adb commands consuming heredoc stdin
    mapfile -t path_array < <(echo "$paths")

    local frame_idx=$frame_offset
    for remote_path in "${path_array[@]}"; do
        [ -z "$remote_path" ] && continue
        local filename=$(basename "$remote_path")
        local ext="${filename##*.}"
        local local_name=$(printf "frame_%03d.%s" "$frame_idx" "$ext")

        # Glasses -> Phone (WiFi P2P)
        PULL_ID="p_$(date +%s%N | cut -c1-13)"
        send_command "pull_glasses_file" "$PULL_ID" "{\"path\": \"$remote_path\"}" > /dev/null
        pull_result=$(poll_result "$PULL_ID" "$PULL_TIMEOUT") || { echo "  Timeout pulling: $filename"; continue; }
        pull_ok=$(echo "$pull_result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null) || true
        if [ "$pull_ok" != "success" ]; then
            echo "  Pull failed: $filename"
            continue
        fi

        # Phone -> PC
        adb exec-out run-as com.repository.listener cat "files/adb_results/$filename" > "$out_dir/$local_name" 2>/dev/null
        local size=$(stat -c%s "$out_dir/$local_name" 2>/dev/null || echo "0")
        info "  $local_name ($((size / 1024 / 1024))MB)"

        # Clean phone temp
        adb shell run-as com.repository.listener rm -f "files/adb_results/$filename" 2>/dev/null || true
        frame_idx=$((frame_idx + 1))
    done

    local pulled=$((frame_idx - frame_offset))
    info "$subdir: $pulled files pulled (offset=$frame_offset)"
    echo "$frame_idx"
}

capture_ground_truth() {
    local scene_dir="$1"
    local out_dir="$scene_dir/$GT_SUBDIR"
    mkdir -p "$out_dir"

    echo ""
    echo "=== Ground Truth: ${GT_TOTAL} frames at ${GT_EXPOSURE_MS}ms in $GT_BATCHES batches ==="
    echo "    Composite exposure: ${GT_COMPOSITE_MS}ms (~$(echo "scale=1; $GT_COMPOSITE_MS / 1000" | bc)s)"

    local frame_offset=0
    for batch in $(seq 1 $GT_BATCHES); do
        echo ""
        echo "--- GT batch $batch/$GT_BATCHES (frames $frame_offset-$((frame_offset + GT_FRAMES_PER_BATCH - 1))) ---"

        # Clear glasses storage before each batch
        CLR_ID="clr_gt_${batch}_$(date +%s)"
        send_command "clear_raw" "$CLR_ID" > /dev/null
        poll_result "$CLR_ID" 30 > /dev/null 2>&1 || true

        frame_offset=$(capture_config "$scene_dir" "$GT_EXPOSURE_MS" "$GT_FRAMES_PER_BATCH" "$GT_SUBDIR" "$frame_offset")
    done

    local total_files=$(ls "$out_dir"/ 2>/dev/null | wc -l)
    info "Ground truth complete: $total_files frames"
}

write_metadata() {
    local scene_dir="$1"
    local scene_num="$2"

    cat > "$scene_dir/metadata.json" << METAEOF
{
    "scene": $scene_num,
    "sensor": {
        "resolution": [4032, 3024],
        "format": "bayer_rggb",
        "bit_depth": 16,
        "black_level": 64,
        "iso": 50,
        "max_exposure_ms": $GT_EXPOSURE_MS
    },
    "ground_truth": {
        "method": "frame_summation",
        "exposure_ms": $GT_EXPOSURE_MS,
        "num_frames": $GT_TOTAL,
        "composite_exposure_ms": $GT_COMPOSITE_MS,
        "note": "Sensor max exposure is ${GT_EXPOSURE_MS}ms. GT composed by summing $GT_TOTAL frames."
    },
    "captures": {
        "short_33ms": {
            "exposure_ms": 33,
            "boost": $BOOST,
            "num_frames": 10,
            "amplification_ratio": $(echo "scale=1; $GT_COMPOSITE_MS / 33" | bc)
        },
        "short_100ms": {
            "exposure_ms": 100,
            "boost": $BOOST,
            "num_frames": 10,
            "amplification_ratio": $(echo "scale=1; $GT_COMPOSITE_MS / 100" | bc)
        },
        "long_313ms": {
            "exposure_ms": $GT_EXPOSURE_MS,
            "boost": $BOOST,
            "num_frames": $GT_TOTAL,
            "amplification_ratio": 1
        }
    },
    "timestamp": "$(date -Iseconds)"
}
METAEOF
    info "Metadata written"
}

generate_reference() {
    local scene_dir="$1"
    local long_dir="$scene_dir/$GT_SUBDIR"

    if [ ! -d "$long_dir" ] || [ -z "$(ls "$long_dir"/ 2>/dev/null)" ]; then
        echo "  No long-exposure frames for reference generation"
        return 1
    fi

    if [ -f "$GENERATE_SCRIPT" ]; then
        python3 "$GENERATE_SCRIPT" "$long_dir" "$scene_dir/reference.png"
        info "Reference PNG generated: $scene_dir/reference.png"
    else
        echo "  generate_reference.py not found at $GENERATE_SCRIPT"
        return 1
    fi
}

# --- Main ---

echo "=== Night Vision Dataset Capture (Learning to See in the Dark) ==="
echo "  Dataset dir: $DATASET_DIR"
echo "  Short inputs: 33ms x10, 100ms x10 (paper: 1/30s, 1/10s)"
echo "  Ground truth: ${GT_EXPOSURE_MS}ms x${GT_TOTAL} summed (~$(echo "scale=1; $GT_COMPOSITE_MS / 1000" | bc)s composite)"
echo "  Amplification: x$(echo "scale=0; $GT_COMPOSITE_MS / 33" | bc) (33ms), x$(echo "scale=0; $GT_COMPOSITE_MS / 100" | bc) (100ms)"
echo "  Per scene: ~$(echo "scale=1; (10 + 10 + $GT_TOTAL) * 24 / 1024" | bc)GB"
echo ""

check_adb

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

# Determine starting scene number
SCENE_NUM=${1:-}
if [ -n "$SCENE_NUM" ]; then
    scene_num=$SCENE_NUM
else
    scene_num=1
    while [ -d "$DATASET_DIR/$(printf 'scene_%03d' $scene_num)" ]; do
        scene_num=$((scene_num + 1))
    done
    echo "Starting at scene $scene_num (next available)"
fi

while true; do
    scene_label=$(printf "scene_%03d" "$scene_num")
    scene_dir="$DATASET_DIR/$scene_label"

    echo ""
    echo "=========================================="
    echo "  SCENE $scene_num ($scene_label)"
    echo "=========================================="

    if [ -d "$scene_dir" ]; then
        echo "WARNING: $scene_label already exists. Overwrite? [y/N]"
        read -r answer
        if [ "$answer" != "y" ] && [ "$answer" != "Y" ]; then
            scene_num=$((scene_num + 1))
            continue
        fi
        rm -rf "$scene_dir"
    fi

    mkdir -p "$scene_dir"

    # Clean glasses storage
    info "Cleaning glasses storage..."
    CLR_ID="clr_$(date +%s)"
    send_command "clear_raw" "$CLR_ID" > /dev/null
    poll_result "$CLR_ID" 30 > /dev/null 2>&1 || true

    # Capture short-exposure inputs
    for config in "${CONFIGS[@]}"; do
        IFS=',' read -r exp_ms num_frames subdir <<< "$config"
        capture_config "$scene_dir" "$exp_ms" "$num_frames" "$subdir" 0 > /dev/null

        CLR_ID="clr_mid_$(date +%s)"
        send_command "clear_raw" "$CLR_ID" > /dev/null
        poll_result "$CLR_ID" 30 > /dev/null 2>&1 || true
    done

    # Capture ground truth (batched to manage glasses storage)
    capture_ground_truth "$scene_dir"

    # Write metadata
    write_metadata "$scene_dir" "$scene_num"

    # Generate reference PNG by summing all GT frames
    info "Generating reference PNG (summing $GT_TOTAL frames)..."
    generate_reference "$scene_dir" || true

    # Summary
    echo ""
    echo "--- Scene $scene_num Summary ---"
    for subdir in short_33ms short_100ms long_313ms; do
        count=$(ls "$scene_dir/$subdir/" 2>/dev/null | wc -l)
        size=$(du -sh "$scene_dir/$subdir" 2>/dev/null | cut -f1)
        echo "  $subdir: $count files ($size)"
    done
    total=$(du -sh "$scene_dir" 2>/dev/null | cut -f1)
    echo "  Total: $total"
    [ -f "$scene_dir/reference.png" ] && echo "  Reference: $scene_dir/reference.png"

    # Prompt for next scene
    echo ""
    echo "Reposition glasses and press Enter for scene $((scene_num + 1)), or 'q' to quit."
    read -r input
    if [ "$input" = "q" ] || [ "$input" = "Q" ]; then
        break
    fi

    scene_num=$((scene_num + 1))
done

echo ""
echo "=== Dataset Capture Complete ==="
echo "Scenes captured: $(ls -d "$DATASET_DIR"/scene_* 2>/dev/null | wc -l)"
echo "Total size: $(du -sh "$DATASET_DIR" 2>/dev/null | cut -f1)"
