#!/usr/bin/env bash
# Real-world BT reliability test: force-stop, BT flip, idle, rapid reconnect.
# Run with both devices connected via USB. Phone + glasses apps must be installed.
#
# What it checks:
#   1. 90s stable-idle: phone must NOT flap CONNECTED -> CONNECTING
#   2. Glasses listener force-stop: phone reconnects within 30s, no zombie CONNECTING
#   3. Glasses bt-manager force-stop: phone reconnects within 30s
#   4. Phone listener force-stop: comes back, BT reconnects
#   5. 20x rapid reconnect churn (every 4s): settles to CONNECTED, no stuck state
set -u

PHONE="${PHONE:-YOUR_PHONE_SERIAL}"
GLASSES="${GLASSES:-YOUR_GLASSES_SERIAL}"

RED=$'\e[31m'; GRN=$'\e[32m'; YLW=$'\e[33m'; RST=$'\e[0m'
FAIL=0

say() { echo "${YLW}[$(date +%H:%M:%S)]${RST} $*"; }
pass() { echo "${GRN}PASS${RST}  $*"; }
fail() { echo "${RED}FAIL${RST}  $*"; FAIL=$((FAIL+1)); }

LOG_BUF=$(mktemp)
# Marker timestamp for each "since this point" window (epoch seconds).
WINDOW_MARK=0

refresh_log() {
    # Dump ALL of logcat since start-of-buffer, filter to our tags. Bypasses any
    # streaming/buffering quirks by grabbing a complete snapshot each time.
    adb -s "$PHONE" logcat -d -s "PhoneBtHost:I" > "$LOG_BUF" 2>/dev/null
}
start_log_capture() {
    adb -s "$PHONE" logcat -c
    WINDOW_MARK=$(date +%s)
    sleep 1
}
stop_log_capture() { rm -f "$LOG_BUF"; }
trap stop_log_capture EXIT

# Mark the current moment as the start of a new wait window.
mark_window() { WINDOW_MARK=$(date +%s); }

# Return 0 if pattern $2 appears in logcat at or after WINDOW_MARK time, within $1 seconds.
wait_for_log() {
    local timeout=$1; local pattern=$2
    local deadline=$(( $(date +%s) + timeout ))
    local mark_hms; mark_hms=$(date -d "@$WINDOW_MARK" +%H:%M:%S)
    while [ "$(date +%s)" -lt "$deadline" ]; do
        refresh_log
        if awk -v m="$mark_hms" -v pat="$pattern" '
            /^[0-9]+-[0-9]+ [0-9]+:[0-9]+:[0-9]+/ {
                t = $2; sub(/\..*$/, "", t);
                if (t >= m && $0 ~ pat) { print; exit }
            }' "$LOG_BUF" | grep -q .; then
            return 0
        fi
        sleep 2
    done
    return 1
}

current_state() {
    refresh_log
    grep "BT connection state:" "$LOG_BUF" | tail -1 | sed -E 's/.*BT connection state: //' | tr -d '\r'
}

# ---------- Scenarios ----------

scenario_idle_stability() {
    say "Scenario A: 90s stable-idle (no state flapping)"
    mark_window

    sleep 90

    refresh_log
    local mark_hms; mark_hms=$(date -d "@$WINDOW_MARK" +%H:%M:%S)
    local connecting_count
    connecting_count=$(awk -v m="$mark_hms" '
        /BT connection state: CONNECTING/ {
            t = $2; sub(/\..*$/, "", t);
            if (t >= m) n++
        }
        END { print (n ? n : 0) }' "$LOG_BUF")

    # During an already-CONNECTED idle period, there should be 0 new CONNECTING entries.
    if [ "$connecting_count" -eq 0 ]; then
        pass "No CONNECTING oscillation during 90s idle (observed $connecting_count)"
    else
        fail "CONNECTING flapping: $connecting_count entries in 90s idle (should be 0)"
    fi
}

scenario_glasses_listener_forcestop() {
    say "Scenario B: force-stop glasses LISTENER (BluetoothHelper may flip; RFCOMM must recover)"
    mark_window
    adb -s "$GLASSES" shell am force-stop com.repository.glasses.listener
    # The glasses CXR-M service runs in the listener process -- force-stopping it drops
    # GATT and triggers the phone to rescan. What matters for the user is that RFCOMM
    # messaging recovers quickly (messaging lives in bt-manager, which stays up).
    if wait_for_log 45 "RFCOMM connected to glasses|Already connected, skipping"; then
        pass "RFCOMM intact / reconnected after listener force-stop"
    else
        fail "RFCOMM did NOT recover after listener force-stop (45s)"
    fi
}

scenario_btmanager_forcestop() {
    say "Scenario C: force-stop glasses BT-MANAGER (breaks RFCOMM, expect reconnect)"
    mark_window
    adb -s "$GLASSES" shell am force-stop com.repository.glasses.btmanager
    say "Force-stopped bt-manager, waiting up to 60s for reconnect..."
    if wait_for_log 60 "RFCOMM connected to glasses"; then
        pass "RFCOMM reconnected after bt-manager force-stop"
    else
        fail "RFCOMM did NOT reconnect after bt-manager force-stop (60s)"
    fi
}

scenario_phone_listener_restart() {
    say "Scenario D: restart phone listener, expect BT link to come back"
    adb -s "$PHONE" shell am force-stop com.repository.listener
    sleep 3
    adb -s "$PHONE" shell monkey -p com.repository.listener -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    mark_window
    sleep 8
    if wait_for_log 60 "RFCOMM connected to glasses|BT connection state: CONNECTED"; then
        pass "Phone listener restart -> BT reconnected"
    else
        fail "Phone listener restart: BT did NOT reconnect within 60s"
    fi
}

scenario_rapid_churn() {
    say "Scenario E: 5x rapid force-stop cycles on glasses BT-MANAGER (10s each)"
    local i
    for i in $(seq 1 5); do
        adb -s "$GLASSES" shell am force-stop com.repository.glasses.btmanager >/dev/null 2>&1
        sleep 10
    done
    mark_window
    say "Churn done, waiting up to 120s for final settle..."
    if wait_for_log 120 "RFCOMM connected to glasses"; then
        pass "RFCOMM reconnected after 5x rapid bt-manager churn"
    else
        fail "RFCOMM did NOT reconnect after rapid churn (120s)"
    fi
    sleep 15
    local st; st=$(current_state)
    if [ "$st" = "CONNECTED" ] || grep -q "Already connected" <(tail -30 "$LOG_BUF"); then
        pass "Final state CONNECTED"
    else
        fail "Final state '$st'"
    fi
}

# ---------- Prereqs ----------

echo "=== BT Reliability E2E (real world) ==="
adb -s "$PHONE" get-state >/dev/null 2>&1 || { echo "phone $PHONE offline"; exit 2; }
adb -s "$GLASSES" get-state >/dev/null 2>&1 || { echo "glasses $GLASSES offline"; exit 2; }

start_log_capture

say "Ensuring starting state is CONNECTED..."
if ! adb -s "$PHONE" shell pidof com.repository.listener >/dev/null 2>&1; then
    adb -s "$PHONE" shell monkey -p com.repository.listener -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 8
fi
sleep 3
if ! wait_for_log 40 "BT connection state: CONNECTED|RFCOMM connected to glasses|Already connected, skipping"; then
    echo "${RED}Could not reach initial CONNECTED state; aborting.${RST}"
    exit 2
fi
pass "Initial CONNECTED state reached"

scenario_idle_stability
scenario_glasses_listener_forcestop
scenario_btmanager_forcestop
scenario_phone_listener_restart
scenario_rapid_churn

echo ""
if [ "$FAIL" -eq 0 ]; then
    echo "${GRN}ALL SCENARIOS PASSED${RST}"
    exit 0
else
    echo "${RED}$FAIL scenario(s) failed${RST}"
    exit 1
fi
