#!/usr/bin/env bash
#
# BT reliability stress test.
#
# Verifies the RFCOMM messaging connection between phone and glasses reestablishes
# within SLA under various failure scenarios. Uses heartbeat messages ('Glasses status:
# heartbeat_screen_on' emitted every ~10s from glasses) as the liveness signal.
#
# Requires both devices connected via ADB:
#   - glasses: YOUR_GLASSES_SERIAL
#   - phone:   YOUR_PHONE_SERIAL
#
# Scenarios (in order):
#   A. kill glasses listener app (bt-manager stays alive, listener auto-restarts)
#   B. kill glasses bt-manager (breaks RFCOMM, must recover via DeathRecipient rebind)
#   C. kill phone listener app (glasses-side must detect stale and recover)
#   D. toggle glasses BT adapter off+on (simulates full reboot at BT level)
#   E. rapid cycle: 10x bt-manager kill+restart

set -u

GLASSES="YOUR_GLASSES_SERIAL"
PHONE="YOUR_PHONE_SERIAL"

GLASSES_PKG="com.repository.glasses.listener"
GLASSES_BTM_PKG="com.repository.glasses.btmanager"
PHONE_PKG="com.repository.listener"

# A heartbeat ("Glasses status: heartbeat_...") arrives every ~10s on the phone while
# messaging is alive. Use its resumption as the reliability signal.
HEARTBEAT_PATTERN='PhoneBtHost: Glasses status: heartbeat'

PASS=0
FAIL=0
RESULTS=()

log() { echo "[$(date +%H:%M:%S)] $*"; }
pass() { log "PASS: $1"; RESULTS+=("PASS: $1"); PASS=$((PASS+1)); }
fail() { log "FAIL: $1"; RESULTS+=("FAIL: $1"); FAIL=$((FAIL+1)); }

# Wait for a heartbeat to land on the phone since a given epoch seconds.
# Returns 0 if a heartbeat with epoch timestamp > since_s arrives within timeout_s.
wait_heartbeat_since() {
    local since_s="$1" timeout_s="$2" what="$3"
    local t0=$(date +%s)
    while :; do
        local line
        line=$(adb -s $PHONE logcat -v epoch -d 2>&1 | grep -E "$HEARTBEAT_PATTERN" | tail -1)
        if [ -n "$line" ]; then
            local ep_s
            ep_s=$(echo "$line" | awk '{print $1}' | cut -d. -f1)
            if [ -n "$ep_s" ] && [ "$ep_s" -gt "$since_s" ] 2>/dev/null; then
                local dt=$(($(date +%s) - t0))
                log "  [$dt s] heartbeat resumed on phone: $what (at $ep_s)"
                return 0
            fi
        fi
        local dt=$(($(date +%s) - t0))
        if [ $dt -ge $timeout_s ]; then
            log "  [timeout $dt s] no heartbeat resumed: $what"
            return 1
        fi
        sleep 2
    done
}

now_s() { date +%s; }

ensure_baseline() {
    log "=== baseline: ensuring messaging alive ==="
    adb -s $GLASSES shell "monkey -p $GLASSES_PKG 1" >/dev/null 2>&1
    adb -s $PHONE   shell "monkey -p $PHONE_PKG 1" >/dev/null 2>&1
    sleep 5
    if wait_heartbeat_since $(now_s) 60 "baseline heartbeat"; then
        pass "baseline: messaging alive"
        return 0
    else
        fail "baseline: no messaging heartbeat within 120s"
        return 1
    fi
}

# Scenario A: kill glasses listener (bt-manager stays up, listener auto-restarts).
scenario_kill_listener() {
    local iter=$1
    log "=== scenario A #$iter: kill glasses listener ==="
    local since=$(now_s)
    adb -s $GLASSES shell "am force-stop $GLASSES_PKG"
    log "  listener killed"
    sleep 2
    adb -s $GLASSES shell "am start -n $GLASSES_PKG/.MainActivity" >/dev/null 2>&1
    if wait_heartbeat_since $since 120 "A#$iter heartbeat after listener kill"; then
        pass "A#$iter: heartbeat resumed after listener kill"
    else
        fail "A#$iter: heartbeat did not resume within 120s after listener kill"
    fi
}

# Scenario B: kill bt-manager (breaks RFCOMM; DeathRecipient in BtManagerBridge must
# trigger unbind+rebind, MessageRelay must reopen server socket).
scenario_kill_btmanager() {
    local iter=$1
    log "=== scenario B #$iter: kill bt-manager ==="
    local since=$(now_s)
    adb -s $GLASSES shell "am force-stop $GLASSES_BTM_PKG"
    log "  bt-manager killed"
    # bt-manager auto-starts when BtManagerBridge rebinds (explicit unbind+bindService).
    if wait_heartbeat_since $since 120 "B#$iter heartbeat after bt-manager kill"; then
        pass "B#$iter: messaging recovered after bt-manager kill"
    else
        fail "B#$iter: messaging did NOT recover within 120s after bt-manager kill"
    fi
}

# Scenario C: kill phone listener (glasses-side watchdog must see stale rx and reopen).
scenario_kill_phone() {
    local iter=$1
    log "=== scenario C #$iter: kill phone listener ==="
    adb -s $PHONE shell "am force-stop $PHONE_PKG"
    log "  phone listener killed"
    sleep 2
    # Phone: main activity lives at top-level package (not under .ui). Service is not exported.
    adb -s $PHONE shell "am start -n $PHONE_PKG/.MainActivity" >/dev/null 2>&1
    local since=$(now_s)
    if wait_heartbeat_since $since 120 "C#$iter heartbeat after phone kill"; then
        pass "C#$iter: messaging recovered after phone kill"
    else
        fail "C#$iter: messaging did NOT recover within 120s after phone kill"
    fi
}

# Scenario D: toggle glasses BT adapter off+on (simulates glasses reboot at BT level).
scenario_toggle_bt() {
    local iter=$1
    log "=== scenario D #$iter: glasses BT toggle ==="
    local since=$(now_s)
    adb -s $GLASSES shell "svc bluetooth disable" >/dev/null 2>&1
    sleep 3
    adb -s $GLASSES shell "svc bluetooth enable" >/dev/null 2>&1
    log "  glasses BT toggled"
    if wait_heartbeat_since $since 120 "D#$iter heartbeat after BT toggle"; then
        pass "D#$iter: messaging recovered after BT toggle"
    else
        fail "D#$iter: messaging did NOT recover within 120s after BT toggle"
    fi
}

# Scenario E: rapid cycle bt-manager restarts (10 in a row).
scenario_rapid_cycle() {
    local n=$1
    log "=== scenario E: $n rapid bt-manager restarts ==="
    local ok=0 ko=0
    for i in $(seq 1 $n); do
        local since=$(now_s)
        adb -s $GLASSES shell "am force-stop $GLASSES_BTM_PKG"
        if wait_heartbeat_since $since 120 "E.$i heartbeat after bt-manager cycle"; then
            ok=$((ok+1))
        else
            ko=$((ko+1))
        fi
        sleep 3
    done
    if [ $ko -eq 0 ]; then
        pass "E: $n/$n rapid cycles recovered"
    else
        fail "E: $ko of $n rapid cycles failed"
    fi
}

main() {
    log "BT reliability stress test"
    adb -s $GLASSES shell "date" >/dev/null 2>&1 || { echo "glasses not connected"; exit 1; }
    adb -s $PHONE   shell "date" >/dev/null 2>&1 || { echo "phone not connected";   exit 1; }

    ensure_baseline || { echo "baseline failed, aborting"; exit 1; }

    for i in 1 2 3; do scenario_kill_listener  $i; sleep 8; done
    for i in 1 2;   do scenario_kill_btmanager $i; sleep 8; done
    for i in 1 2;   do scenario_kill_phone     $i; sleep 8; done
    scenario_toggle_bt 1
    sleep 10
    scenario_rapid_cycle 10

    echo
    echo "================================"
    echo "  STRESS TEST RESULTS"
    echo "================================"
    echo "  PASS: $PASS"
    echo "  FAIL: $FAIL"
    echo
    for r in "${RESULTS[@]}"; do echo "  $r"; done
    [ $FAIL -eq 0 ]
}

main "$@"
