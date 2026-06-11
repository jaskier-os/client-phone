#!/usr/bin/env bash
# Autonomous RC chat hang reproduction test.
# Types a message into the RC activity, hits send, and tails logs from
# phone logcat, local pc-agent, and remote orchestrator concurrently.
# Reports whether the message round-trip completed within a timeout.

set -u
PHONE="${PHONE:-YOUR_PHONE_SERIAL}"
MSG="${MSG:-autotest-$(date +%s)}"
TIMEOUT="${TIMEOUT:-20}"
REPO="/workspace/project"
PCLOG="$REPO/.service-logs/pc-agent.log"
ORCH_POD="orchestrator-7c666c785c-6gj5g"
ORCH_NS="ai"
SSH="ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=no root@your-server.example.com -p 41922"

OUT=$(mktemp -d)
echo "[tool] work dir: $OUT"
echo "[tool] message : $MSG"

# Snapshot log offsets BEFORE action
PC_START=$(wc -c <"$PCLOG")
adb -s "$PHONE" logcat -c

# Start async tails (kill on exit)
adb -s "$PHONE" logcat -v time \
  >"$OUT/logcat.txt" &
LPID=$!

( $SSH "k3s kubectl -n $ORCH_NS logs -f $ORCH_POD --since=1s 2>/dev/null | grep --line-buffered -iE 'rc_user|rc-handler|remote-control|phone-01|rc_message|rc_session|No active'" \
  >"$OUT/orch.txt" ) &
OPID=$!

trap 'kill $LPID $OPID 2>/dev/null || true' EXIT
sleep 1  # let tails attach

# Ensure RC activity is focused
FOCUS=$(adb -s "$PHONE" shell dumpsys window | grep -m1 mCurrentFocus)
echo "[tool] focus: $FOCUS"
case "$FOCUS" in *RemoteControlActivity*) ;; *)
  echo "[tool] ERROR: RemoteControlActivity not in focus"; exit 2 ;;
esac

# Tap input field, clear, type, tap send
adb -s "$PHONE" shell input tap 410 1420
sleep 0.3
adb -s "$PHONE" shell input keyevent KEYCODE_MOVE_END
for _ in 1 2 3 4 5 6 7 8 9 10; do adb -s "$PHONE" shell input keyevent KEYCODE_DEL; done
sleep 0.2
adb -s "$PHONE" shell input text "$MSG"
sleep 0.4

# Re-dump UI to find rcSendButton (only present when input non-empty)
adb -s "$PHONE" shell uiautomator dump /sdcard/ui.xml >/dev/null
adb -s "$PHONE" pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
SEND_BOUNDS=$(python3 -c "
import re,sys
s=open('$OUT/ui.xml').read()
m=re.search(r'resource-id=\"com\.repository\.listener:id/rcSendButton\"[^/]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',s)
if not m: sys.exit(1)
x1,y1,x2,y2=map(int,m.groups())
print((x1+x2)//2,(y1+y2)//2)
")
if [ -z "$SEND_BOUNDS" ]; then
  echo "[tool] ERROR: rcSendButton not visible after typing"
  exit 3
fi
echo "[tool] send button at: $SEND_BOUNDS"
T0=$(date +%s)
adb -s "$PHONE" shell input tap $SEND_BOUNDS

# Wait for phone to log receipt of rc_message from orchestrator
REPLY=""
for _ in $(seq 1 "$TIMEOUT"); do
  sleep 1
  if grep -qE 'type=rc_message|onRcMessage|TYPE_RC_MESSAGE|RC message' "$OUT/logcat.txt"; then
    REPLY=$(grep -E 'type=rc_message|onRcMessage|TYPE_RC_MESSAGE|RC message|WS frame' "$OUT/logcat.txt" | head -10)
    break
  fi
done
T1=$(date +%s)
ELAPSED=$((T1-T0))

# Final UI state
adb -s "$PHONE" shell uiautomator dump /sdcard/ui.xml >/dev/null
adb -s "$PHONE" pull /sdcard/ui.xml "$OUT/ui_after.xml" >/dev/null 2>&1
HAS_THINKING=$(grep -c 'rcThinkingView' "$OUT/ui_after.xml" || true)

echo
echo "=========== RESULT ============"
echo "elapsed: ${ELAPSED}s  (timeout=${TIMEOUT}s)"
if [ -n "$REPLY" ]; then
  echo "STATUS: response seen in orchestrator"
else
  echo "STATUS: NO response (HANG)"
fi
echo
echo "-- orchestrator (filtered) --"
tail -20 "$OUT/orch.txt"
echo
echo "-- pc-agent (since test start) --"
tail -c +"$((PC_START+1))" "$PCLOG" | grep -iE 'remote-sessions|session=|RC' | tail -20
echo
echo "-- phone logcat RC-relevant lines --"
grep -iE 'OrchestratorClient|RemoteControl|RC_|RemoteSession|rc_message|rcMessage|onRc|ACTION_RC|WS frame' "$OUT/logcat.txt" | grep -vE 'GLASSES MIC|AudioRecorder|PipelineTracer|Surface|onChunk' | tail -40
echo
echo "artifacts in: $OUT"
