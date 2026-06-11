#!/usr/bin/env bash
# Push test audio to phone and run streaming transcription test.
# Usage: bash test/adb/test_streaming.sh [--direct|--relay|--pipeline] [pcm_file]
#
# Modes:
#   --relay     via orchestrator WS relay (own WebSocket, default)
#   --direct    direct to transcriber (own WebSocket, bypass orchestrator)
#   --pipeline  through real OrchestratorClient (connectTranscribeStream + sendTranscribeAudioFrame)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

# Parse args
MODE="${1:---relay}"
PCM_FILE="${2:-}"

# Default PCM file: look in transcriber tests
if [ -z "$PCM_FILE" ]; then
    PCM_FILE="/workspace/project/sub-module/infrastructure/transcriber/tests/test_30s.pcm"
fi

if [ ! -f "$PCM_FILE" ]; then
    fail "PCM file not found: $PCM_FILE"
fi

DEVICE_PCM="/data/local/tmp/test_streaming.pcm"

check_adb

# Push PCM file
echo "Pushing $(basename "$PCM_FILE") to device..."
adb push "$PCM_FILE" "$DEVICE_PCM"

# Build JSON params based on mode
DIRECT_FLAG="false"
PIPELINE_FLAG="false"
case "$MODE" in
    --direct)   DIRECT_FLAG="true" ;;
    --pipeline) PIPELINE_FLAG="true" ;;
    --relay)    ;; # defaults
    *) fail "Unknown mode: $MODE (use --relay, --direct, or --pipeline)" ;;
esac

# Send command -- bypass send_command to handle JSON escaping for device shell
echo "Starting streaming test (mode=$MODE)..."
CMD_ID="stream_$(date +%s%N | cut -c1-13)"
adb shell "am broadcast \
  -n com.repository.listener/.adb.AdbCommandReceiver \
  -a com.repository.listener.ADB_COMMAND \
  --es type test_streaming \
  --es command_id $CMD_ID \
  --es params '{\"pcm_file\":\"/data/local/tmp/test_streaming.pcm\",\"direct\":$DIRECT_FLAG,\"pipeline\":$PIPELINE_FLAG}'" > /dev/null

# Calculate timeout from audio duration
AUDIO_DUR=$(python3 -c "import os; print(os.path.getsize('$PCM_FILE') / 32000)")
TIMEOUT=$(python3 -c "print(int($AUDIO_DUR + 30))")
echo "Audio: ${AUDIO_DUR}s, timeout: ${TIMEOUT}s"

RESULT=$(poll_result "$CMD_ID" "$TIMEOUT")
assert_json_field "$RESULT" "status" "success"

# Display summary
echo ""
echo "=== STREAMING TEST RESULTS ($MODE) ==="
echo "$RESULT" | python3 -c "
import sys, json
r = json.load(sys.stdin)
d = r.get('data', {})
s = d.get('summary', {})
print(f'Audio duration : {d.get(\"audio_duration_s\", 0):.1f}s')
print(f'Wall time      : {d.get(\"wall_time_s\", 0):.1f}s')
print(f'Partials       : {s.get(\"partials\", 0)}')
print(f'Finals         : {s.get(\"finals\", 0)}')
print(f'Translations   : {s.get(\"translations\", 0)}')
print(f'First partial  : {s.get(\"first_partial_s\", 0):.2f}s')
print(f'Avg gap        : {s.get(\"avg_partial_gap_s\", 0):.2f}s')
print(f'Max gap        : {s.get(\"max_partial_gap_s\", 0):.2f}s')
print()
print('--- Event timeline ---')
for e in d.get('events', []):
    t = e.get('t', 0)
    typ = e.get('type', '')
    if typ in ('partial', 'final', 'transcription_final'):
        print(f'  [{t:6.2f}s] {typ:20s} seg={e.get(\"segId\",0)} {e.get(\"text\",\"\")[:80]}')
    elif typ == 'translation':
        print(f'  [{t:6.2f}s] {typ:20s} seg={e.get(\"segId\",0)} {e.get(\"text\",\"\")[:80]}')
    else:
        txt = e.get('text', '')
        extra = f' {txt}' if txt else ''
        print(f'  [{t:6.2f}s] {typ}{extra}')
"

pass "Streaming test complete"
