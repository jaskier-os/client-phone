#!/bin/bash
# E2E UI test for Remote Control session on phone
# Usage: bash test_rc_ui.sh [device_serial]
set -e

DEVICE="${1:-YOUR_PHONE_SERIAL}"
ADB="adb -s $DEVICE"
SCREENSHOT_DIR="/tmp/rc-ui-test-$(date +%s)"
mkdir -p "$SCREENSHOT_DIR"
STEP=0

screenshot() {
    STEP=$((STEP + 1))
    local name="${STEP}_${1}"
    $ADB shell screencap -p "/data/local/tmp/rc_test_${name}.png"
    $ADB pull "/data/local/tmp/rc_test_${name}.png" "$SCREENSHOT_DIR/${name}.png" 2>/dev/null
    echo "[screenshot] $SCREENSHOT_DIR/${name}.png"
}

wait_for_activity() {
    local target="$1"
    local timeout="${2:-30}"
    for i in $(seq 1 $timeout); do
        local focus=$($ADB shell dumpsys window | grep mCurrentFocus | grep -o "$target" || true)
        if [ -n "$focus" ]; then
            echo "[ok] $target is foreground"
            return 0
        fi
        sleep 1
    done
    echo "[FAIL] $target not found after ${timeout}s"
    return 1
}

wait_for_claude() {
    local timeout="${1:-30}"
    for i in $(seq 1 $timeout); do
        local count=$(ps aux | grep "claude.*print.*remote-control" | grep -v grep | wc -l)
        if [ "$count" -gt 0 ]; then
            echo "[ok] Claude process running"
            return 0
        fi
        sleep 1
    done
    echo "[FAIL] Claude not started after ${timeout}s"
    return 1
}

check_orchestrator_log() {
    local pattern="$1"
    ssh root@your-server.example.com -p 41922 "k3s kubectl logs -n ai \$(k3s kubectl get pods -n ai -l app=orchestrator -o jsonpath='{.items[0].metadata.name}') --tail=30 2>&1" | grep -v "type=health" | grep -i "$pattern" | tail -3
}

tap_send() {
    # Hide keyboard first, then tap send button at known position
    $ADB shell input keyevent KEYCODE_ESCAPE
    sleep 0.5
    # Get send button position dynamically
    $ADB shell uiautomator dump /data/local/tmp/ui.xml 2>/dev/null
    local bounds=$($ADB shell cat /data/local/tmp/ui.xml | grep -oP 'ImageView[^>]*clickable="true"[^>]*bounds="\[(\d+),2\d{3}\]\[(\d+),2\d{3}\]"' | grep -oP '\d+' | head -4)
    if [ -z "$bounds" ]; then
        # Fallback to known position
        $ADB shell input tap 1000 2143
    else
        local x1=$(echo "$bounds" | sed -n 1p)
        local y1=$(echo "$bounds" | sed -n 2p)
        local x2=$(echo "$bounds" | sed -n 3p)
        local y2=$(echo "$bounds" | sed -n 4p)
        local cx=$(( (x1 + x2) / 2 ))
        local cy=$(( (y1 + y2) / 2 ))
        $ADB shell input tap $cx $cy
    fi
}

type_and_send() {
    local msg="$1"
    $ADB shell input tap 400 2150
    sleep 0.5
    # Replace spaces with %s for adb input text
    local encoded=$(echo "$msg" | sed 's/ /%s/g')
    $ADB shell input text "$encoded"
    sleep 0.3
    tap_send
}

echo "========================================"
echo "RC UI E2E Test - $(date)"
echo "Screenshots: $SCREENSHOT_DIR"
echo "========================================"

# --- SETUP ---
echo ""
echo "=== SETUP: Kill old sessions, restart pc-agent ==="
pkill -f "claude.*print.*remote-control" 2>/dev/null || true
systemctl --user restart pc-agent
sleep 5

echo "=== SETUP: Wait for orchestrator redeploy ==="
sleep 10  # Assume already deployed from previous push

echo "=== SETUP: Fresh app start ==="
$ADB shell am force-stop com.repository.listener
sleep 2
$ADB shell am start -n com.repository.listener/.MainActivity
sleep 5

# --- TEST 1: Create session ---
echo ""
echo "=== TEST 1: Create RC session ==="
$ADB shell input tap 231 2200  # Chat tab
sleep 1
$ADB shell input tap 956 2008  # RC FAB
sleep 3
screenshot "01_bottom_sheet"
$ADB shell input tap 540 2005  # Repository
echo "Waiting for session (25s)..."
sleep 25

wait_for_activity "RemoteControlActivity" 5
wait_for_claude 5
screenshot "02_session_opened"

# --- TEST 2: Send message + permission flow ---
echo ""
echo "=== TEST 2: Send message triggering Write tool ==="
type_and_send "Create file /tmp/rc-ui-test.txt with content TestContent"
echo "Waiting for permission buttons (25s)..."
sleep 25
screenshot "03_permission_buttons"

# Check that action buttons are visible and input is hidden
$ADB shell uiautomator dump /data/local/tmp/ui.xml 2>/dev/null
HAS_APPROVE=$($ADB shell cat /data/local/tmp/ui.xml | grep -c 'text="Approve"' || true)
HAS_INPUT=$($ADB shell cat /data/local/tmp/ui.xml | grep -c 'rcInputBar.*VISIBLE\|rcInput.*focusable="true"' || true)
echo "Approve button visible: $HAS_APPROVE"
echo "Input bar check: $HAS_INPUT"

if [ "$HAS_APPROVE" -gt 0 ]; then
    echo "[ok] Inline action buttons showing"
else
    echo "[FAIL] No Approve button found"
fi

# --- TEST 3: Approve permission ---
echo ""
echo "=== TEST 3: Approve permission ==="
# Find and tap Approve
APPROVE_BOUNDS=$($ADB shell cat /data/local/tmp/ui.xml | grep -oP 'text="Approve"[^>]*bounds="\[\K[0-9,]+\]\[[0-9,]+' | head -1)
if [ -n "$APPROVE_BOUNDS" ]; then
    AX=$(echo "$APPROVE_BOUNDS" | grep -oP '^\d+')
    AY=$(echo "$APPROVE_BOUNDS" | grep -oP ',\K\d+' | head -1)
    AX2=$(echo "$APPROVE_BOUNDS" | grep -oP '\]\[\K\d+')
    AY2=$(echo "$APPROVE_BOUNDS" | grep -oP '\]\[\d+,\K\d+')
    CX=$(( (AX + AX2) / 2 ))
    CY=$(( (AY + AY2) / 2 ))
    $ADB shell input tap $CX $CY
    echo "Tapped Approve at ($CX, $CY)"
else
    echo "[FAIL] Could not find Approve button"
fi

echo "Waiting for tool execution (20s)..."
sleep 20
screenshot "04_after_approve"

# Check file created
if [ -f /tmp/rc-ui-test.txt ]; then
    echo "[ok] File created: $(cat /tmp/rc-ui-test.txt)"
else
    echo "[info] File not at /tmp/rc-ui-test.txt (claude may use different path)"
fi

# Check permission card color changed (green = approved)
echo "Orchestrator logs:"
check_orchestrator_log "Permission response"

# --- TEST 4: Check no "calling" status bar ---
echo ""
echo "=== TEST 4: Verify no 'calling' center status ==="
$ADB shell uiautomator dump /data/local/tmp/ui.xml 2>/dev/null
CALLING=$($ADB shell cat /data/local/tmp/ui.xml | grep -c 'calling' || true)
echo "Calling text instances in UI: $CALLING (should be 0 or only in cards)"

# --- TEST 5: Mode selector ---
echo ""
echo "=== TEST 5: Check mode selector options ==="
# Tap mode chip
$ADB shell uiautomator dump /data/local/tmp/ui.xml 2>/dev/null
MODE_BOUNDS=$($ADB shell cat /data/local/tmp/ui.xml | grep -oP 'rcModeSelector[^>]*bounds="\[\K[0-9,]+\]\[[0-9,]+')
if [ -n "$MODE_BOUNDS" ]; then
    MX=$(echo "$MODE_BOUNDS" | grep -oP '^\d+')
    MY=$(echo "$MODE_BOUNDS" | grep -oP ',\K\d+' | head -1)
    MX2=$(echo "$MODE_BOUNDS" | grep -oP '\]\[\K\d+')
    MY2=$(echo "$MODE_BOUNDS" | grep -oP '\]\[\d+,\K\d+')
    MCX=$(( (MX + MX2) / 2 ))
    MCY=$(( (MY + MY2) / 2 ))
    $ADB shell input tap $MCX $MCY
    sleep 1
    screenshot "05_mode_popup"
    # Dismiss popup
    $ADB shell input keyevent KEYCODE_BACK
fi

# --- TEST 6: Stop button visibility ---
echo ""
echo "=== TEST 6: Stop button ==="
# Send another message to trigger thinking
type_and_send "What is 2+2"
sleep 3
screenshot "06_stop_button"
$ADB shell uiautomator dump /data/local/tmp/ui.xml 2>/dev/null
STOP_VISIBLE=$($ADB shell cat /data/local/tmp/ui.xml | grep -c 'rcStopButton' || true)
echo "Stop button in UI: $STOP_VISIBLE"

# Wait for response
sleep 15
screenshot "07_after_response"

# --- SUMMARY ---
echo ""
echo "========================================"
echo "Test complete. Screenshots in: $SCREENSHOT_DIR"
echo "========================================"
ls -1 "$SCREENSHOT_DIR/"
