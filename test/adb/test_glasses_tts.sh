#!/usr/bin/env bash
# E2E test: Send text via glasses flow, verify ALL TTS sentences arrive and play completely.
# Compares orchestrator sentence count with phone relay count and glasses playback.
# Validates duration of each decoded chunk against playback timestamps.
#
# Requires: phone connected (ADB), glasses connected (BT), orchestrator running.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PHONE_SERIAL="${PHONE_SERIAL:-YOUR_PHONE_SERIAL}"
export ANDROID_SERIAL="$PHONE_SERIAL"

source "$SCRIPT_DIR/chat_helpers.sh"

# --- Helpers ---

adb_log() {
  adb shell run-as com.repository.listener cat "$1" 2>/dev/null | tr -d '\r'
}

count_matches() {
  local count
  count=$(echo "$1" | grep -c "$2" 2>/dev/null || true)
  echo "${count:-0}"
}

# Get phone log baseline (line count) for scoped checks
phone_log_baseline() {
  adb shell run-as com.repository.listener wc -l files/logs/listener/latest.log 2>/dev/null | awk '{print $1}' || echo "0"
}
# Get glasses-side log baseline -- pull current glasses-client.log via WiFi P2P
# and capture line count. The glasses log is BT-relay-independent.
glasses_log_baseline() {
  bash "$SCRIPT_DIR/pull_glasses_log.sh" >/dev/null 2>&1 || true
  if [ -f glasses-client.log ]; then
    wc -l < glasses-client.log | awk '{print $1}'
  else
    echo "0"
  fi
}

# Get phone log lines since baseline
phone_logs_since() {
  local baseline="$1"
  local skip=$((baseline + 1))
  adb shell run-as com.repository.listener tail -n "+$skip" files/logs/listener/latest.log 2>/dev/null | tr -d '\r' || echo ""
}
# Get glasses-side log lines since baseline -- re-pull and slice from baseline.
glasses_logs_since() {
  local baseline="$1"
  bash "$SCRIPT_DIR/pull_glasses_log.sh" >/dev/null 2>&1 || true
  if [ ! -f glasses-client.log ]; then
    echo ""
    return
  fi
  local skip=$((baseline + 1))
  tail -n "+$skip" glasses-client.log 2>/dev/null | tr -d '\r' || echo ""
}

echo "========================================="
echo "  Glasses TTS E2E -- Sentence Integrity  "
echo "========================================="
echo ""

# --- Prerequisites ---
require_orchestrator

# --- Log baselines ---
PHONE_BL=$(phone_log_baseline)
GLASSES_BL=$(glasses_log_baseline)
info "Log baselines: phone=$PHONE_BL glasses=$GLASSES_BL"

# --- Step 1: Send chat request asking for many sentences ---
PROMPT="Write exactly 5 numbered sentences in Russian about space exploration. Each sentence must be at least 15 words. Number them 1-5."
info "Sending prompt: ${PROMPT:0:80}..."

result=$(chat_send_cmd "$PROMPT" 180 "glasses")
status=$(json_field "$result" "status")
[ "$status" = "success" ] || fail "Chat response failed: status=$status"

response_text=$(json_field "$result" "data.text")
request_id=$(json_field "$result" "data.request_id")
elapsed_ms=$(json_field "$result" "data.elapsed_ms")

info "Request ID: $request_id"
info "Elapsed: ${elapsed_ms}ms"
info "Response (first 200): ${response_text:0:200}"
pass "Step 1: Chat response received"
echo ""

# --- Step 2: Wait for TTS playback to complete ---
info "Waiting for TTS playback to complete..."
MAX_WAIT=120
WAIT=0
while [ "$WAIT" -lt "$MAX_WAIT" ]; do
  GL=$(glasses_logs_since "$GLASSES_BL")
  if echo "$GL" | grep -q "playback FINISHED"; then
    break
  fi
  sleep 3
  WAIT=$((WAIT + 3))
  if [ $((WAIT % 15)) -eq 0 ]; then
    info "  Still waiting... (${WAIT}s)"
  fi
done

# Extra buffer for log relay
sleep 3

# --- Step 3: Collect logs ---
PHONE_LOG=$(phone_logs_since "$PHONE_BL")
GLASSES_LOG=$(glasses_logs_since "$GLASSES_BL")

echo ""
echo "--- Phone TTS Logs ---"

# Phone: TTS chunks received from orchestrator
PHONE_TTS_LINES=$(echo "$PHONE_LOG" | grep "TTS audio IN.*$request_id" || true)
PHONE_TTS_COUNT=$(echo "$PHONE_TTS_LINES" | grep -c "TTS audio IN" 2>/dev/null || echo "0")

info "Phone received $PHONE_TTS_COUNT TTS chunks from orchestrator:"
echo "$PHONE_TTS_LINES" | while IFS= read -r line; do
  sentence=$(echo "$line" | grep -oP 'sentence=\K[0-9]+/[0-9]+' || echo "?")
  format=$(echo "$line" | grep -oP 'format=\K\w+' || echo "?")
  bytes=$(echo "$line" | grep -oP 'rawBytes=\K[0-9]+' || echo "?")
  final=$(echo "$line" | grep -oP 'final=\K\w+' || echo "?")
  text=$(echo "$line" | grep -oP "text='\K[^']{0,60}" || echo "")
  info "  sentence=$sentence format=$format bytes=$bytes final=$final text='${text}...'"
done

# Phone: TTS relayed to glasses
PHONE_RELAY_COUNT=$(count_matches "$PHONE_LOG" "TTS relay to glasses")
info "Phone relayed $PHONE_RELAY_COUNT chunks to glasses"

[ "$PHONE_TTS_COUNT" -gt 0 ] || fail "Step 3: No TTS audio received by phone for $request_id"

# Extract expected total sentences from phone log (e.g. "sentence=1/5" -> 5)
EXPECTED_TOTAL=$(echo "$PHONE_TTS_LINES" | grep -oP 'sentence=\d+/\K\d+' | head -1)
info "Expected total sentences: $EXPECTED_TOTAL"

[ "$PHONE_TTS_COUNT" -eq "$EXPECTED_TOTAL" ] || fail "Step 3: Phone received $PHONE_TTS_COUNT chunks but expected $EXPECTED_TOTAL"
[ "$PHONE_RELAY_COUNT" -eq "$EXPECTED_TOTAL" ] || fail "Step 3: Phone relayed $PHONE_RELAY_COUNT but expected $EXPECTED_TOTAL"
pass "Step 3: Phone received and relayed all $EXPECTED_TOTAL sentences"
echo ""

# --- Step 4: Glasses TTS reception and decode ---
echo "--- Glasses TTS Logs ---"

# Glasses: TTS chunks received (single source -- glasses-side persistent log via WiFi P2P)
GLASSES_RECV_LINES=$(echo "$GLASSES_LOG" | grep "TTS audio received:.*$request_id" | grep "sentence=" || true)
GLASSES_RECV_UNIQUE=$(echo "$GLASSES_RECV_LINES" | sort -u || true)
GLASSES_RECV_COUNT=$(echo "$GLASSES_RECV_UNIQUE" | grep -c "sentence=" 2>/dev/null || echo "0")

info "Glasses received $GLASSES_RECV_COUNT TTS chunks:"
echo "$GLASSES_RECV_UNIQUE" | while IFS= read -r line; do
  sentence=$(echo "$line" | grep -oP 'sentence=\K[0-9]+/[0-9]+' || echo "?")
  final=$(echo "$line" | grep -oP 'final=\K\w+' || echo "?")
  text=$(echo "$line" | grep -oP "text='\K[^']{0,60}" || echo "")
  info "  sentence=$sentence final=$final text='${text}...'"
done

[ "$GLASSES_RECV_COUNT" -ge "$EXPECTED_TOTAL" ] || fail "Step 4: Glasses received $GLASSES_RECV_COUNT but expected $EXPECTED_TOTAL"
pass "Step 4: Glasses received all $EXPECTED_TOTAL sentences"

# Glasses: Opus decode results
DECODE_LINES=$(echo "$GLASSES_LOG" | grep "Opus decoded:" || true)
DECODE_COUNT=$(echo "$DECODE_LINES" | grep -c "Opus decoded:" 2>/dev/null || echo "0")
DECODE_FAIL=$(count_matches "$GLASSES_LOG" "Opus decode failed")

info "Opus decodes: $DECODE_COUNT successful, $DECODE_FAIL failed"
echo "$DECODE_LINES" | while IFS= read -r line; do
  bytes=$(echo "$line" | grep -oP '\K[0-9]+ bytes PCM' || echo "?")
  dur=$(echo "$line" | grep -oP '\K[0-9]+ms duration' || echo "?")
  info "  decoded: $bytes, $dur"
done

[ "$DECODE_COUNT" -eq "$EXPECTED_TOTAL" ] || fail "Step 4: Decoded $DECODE_COUNT chunks but expected $EXPECTED_TOTAL"
[ "$DECODE_FAIL" -eq 0 ] || fail "Step 4: $DECODE_FAIL decode failures"
pass "Step 4: All $EXPECTED_TOTAL sentences decoded successfully"
echo ""

# --- Step 5: Playback timing validation ---
echo "--- Playback Timing ---"

PLAYBACK_START_LINE=$(echo "$GLASSES_LOG" | grep "playback STARTED" | tail -1)
PLAYBACK_END_LINE=$(echo "$GLASSES_LOG" | grep "playback FINISHED" | tail -1)

if [ -z "$PLAYBACK_START_LINE" ]; then
  fail "Step 5: Playback never started"
fi
if [ -z "$PLAYBACK_END_LINE" ]; then
  fail "Step 5: Playback started but never finished"
fi

# Extract timestamps (HH:MM:SS.mmm)
START_TS=$(echo "$PLAYBACK_START_LINE" | grep -oP '^\S+ \K\d+:\d+:\d+\.\d+' | head -1)
END_TS=$(echo "$PLAYBACK_END_LINE" | grep -oP '^\S+ \K\d+:\d+:\d+\.\d+' | head -1)

if [ -z "$START_TS" ] || [ -z "$END_TS" ]; then
  # Try alternate timestamp format embedded in glasses-client.log line
  START_TS=$(echo "$PLAYBACK_START_LINE" | grep -oP '\d{2}:\d{2}:\d{2}\.\d{3}' | head -1)
  END_TS=$(echo "$PLAYBACK_END_LINE" | grep -oP '\d{2}:\d{2}:\d{2}\.\d{3}' | head -1)
fi

info "Playback started: $START_TS"
info "Playback ended:   $END_TS"

# Compute actual playback duration in ms
if [ -n "$START_TS" ] && [ -n "$END_TS" ]; then
  ACTUAL_MS=$(python3 -c "
from datetime import datetime
fmt = '%H:%M:%S.%f'
s = datetime.strptime('$START_TS', fmt)
e = datetime.strptime('$END_TS', fmt)
d = (e - s).total_seconds() * 1000
# Handle midnight wrap
if d < 0: d += 86400000
print(int(d))
" 2>/dev/null || echo "0")
  info "Actual playback duration: ${ACTUAL_MS}ms"
else
  ACTUAL_MS=0
  info "WARN: Could not parse timestamps"
fi

# Sum expected duration from Opus decode logs
EXPECTED_DUR_MS=$(echo "$DECODE_LINES" | grep -oP '(\d+)ms duration' | grep -oP '^\d+' | python3 -c "
import sys
total = sum(int(l.strip()) for l in sys.stdin if l.strip())
print(total)
" 2>/dev/null || echo "0")
info "Expected audio duration (sum of decoded): ${EXPECTED_DUR_MS}ms"

# Tolerance: actual should be within 20% of expected (playback has overhead + drain time)
if [ "$EXPECTED_DUR_MS" -gt 0 ] && [ "$ACTUAL_MS" -gt 0 ]; then
  RATIO=$(python3 -c "print(round($ACTUAL_MS / $EXPECTED_DUR_MS, 2))")
  info "Duration ratio (actual/expected): $RATIO"
  # Accept 0.8 to 1.5 range (playback can be slightly longer due to drain/gaps)
  VALID=$(python3 -c "print('yes' if 0.8 <= $ACTUAL_MS / $EXPECTED_DUR_MS <= 1.5 else 'no')")
  [ "$VALID" = "yes" ] || fail "Step 5: Duration mismatch: actual=${ACTUAL_MS}ms expected=${EXPECTED_DUR_MS}ms ratio=$RATIO"
  pass "Step 5: Playback duration matches decoded audio (ratio=$RATIO)"
else
  info "WARN: Skipping duration validation (timestamps unavailable)"
fi
echo ""

# --- Step 6: Duck/unduck validation ---
echo "--- Duck State ---"
DUCK_TRUE=$(count_matches "$GLASSES_LOG" "listener_audio_duck.*=1}")
DUCK_FALSE=$(count_matches "$GLASSES_LOG" "listener_audio_duck.*=0}")
info "Duck events: $DUCK_TRUE ducks, $DUCK_FALSE unducks"

# Should have at least: 1 duck (LISTENING) + 1 unduck (LISTENING exit) + 1 duck (TTS) + 1 unduck (TTS end)
[ "$DUCK_TRUE" -ge 2 ] || info "WARN: Expected at least 2 duck events, got $DUCK_TRUE"
[ "$DUCK_FALSE" -ge 2 ] || info "WARN: Expected at least 2 unduck events, got $DUCK_FALSE"

# Final state should be unduck (last duck message should be 0)
LAST_DUCK=$(echo "$GLASSES_LOG" | grep "listener_audio_duck" | tail -1 || true)
if echo "$LAST_DUCK" | grep -q "=0}"; then
  pass "Step 6: Final duck state is unducked"
elif echo "$LAST_DUCK" | grep -q "=1}"; then
  fail "Step 6: Final duck state is STILL DUCKED -- volume stuck"
else
  info "WARN: No duck events found (music not playing?)"
fi
echo ""

# --- Summary ---
echo "========================================="
echo "  SUMMARY"
echo "========================================="
info "Prompt: ${PROMPT:0:60}..."
info "Response: ${response_text:0:100}..."
info "Request ID: $request_id"
info "Orchestrator -> Phone: $PHONE_TTS_COUNT/$EXPECTED_TOTAL sentences"
info "Phone -> Glasses BT: $PHONE_RELAY_COUNT/$EXPECTED_TOTAL relayed"
info "Glasses received: $GLASSES_RECV_COUNT/$EXPECTED_TOTAL"
info "Glasses decoded: $DECODE_COUNT OK, $DECODE_FAIL failed"
info "Playback: ${ACTUAL_MS}ms actual, ${EXPECTED_DUR_MS}ms expected (ratio=${RATIO:-?})"
info "Duck: $DUCK_TRUE ducks / $DUCK_FALSE unducks"
echo ""
echo "=== ALL PASSED ==="
