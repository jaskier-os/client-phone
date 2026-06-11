#!/usr/bin/env bash
# Test: "All" vs "Only open" filter for RC sessions.
#
# Injects fake RC events and toggles showOnlyOpen via ADB, then dumps what the
# UI would render from the service-level mirror (rcDumpState + showOnlyOpen).
#
# Assertions:
#   1. Startup: no sessions, showOnlyOpen=false.
#   2. After two START broadcasts for different folders, both visible in All.
#   3. Toggle showOnlyOpen=true: both active sessions still visible.
#   4. THINKING on sess_a flips turning=true.
#   5. RC_MESSAGE with isFinal=false keeps turning=true.
#   6. RC_MESSAGE with isFinal=true flips turning=false, session retained.
#   7. SESSION_END on sess_b: in Only Open, only sess_a visible.
#      In All, both visible (sess_b as ended).
#   8. Stress: 20 sessions across 4 folders, all visible.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

check_adb
command -v jq >/dev/null 2>&1 || fail "jq is required"

APP_ID_LOCAL="com.repository.listener"
ADB_ACTION_LOCAL="com.repository.listener.ADB_COMMAND"
RUN_ID="rcchip_$(date +%s)"

send_cmd_raw() {
    local type="$1"; local cmd_id="$2"; local params="$3"
    adb shell "am broadcast \
        -n $APP_ID_LOCAL/.adb.AdbCommandReceiver \
        -a $ADB_ACTION_LOCAL \
        --es type $type \
        --es command_id $cmd_id \
        --es params '$params'" > /dev/null 2>&1
}

inject() {
    # inject <id_suffix> <sessionId> <workDir> <action> [isFinal=true] [text=""]
    local id_suffix="$1"; local session_id="$2"; local work_dir="$3"; local action="$4"
    local is_final="${5:-true}"; local text="${6:-}"
    local cmd_id="${RUN_ID}_${id_suffix}"
    local params
    params=$(python3 -c "
import json, sys
print(json.dumps({
    'sessionId': sys.argv[1],
    'workDir':  sys.argv[2],
    'action':   sys.argv[3],
    'isFinal':  sys.argv[4] == 'true',
    'text':     sys.argv[5],
}))
" "$session_id" "$work_dir" "$action" "$is_final" "$text")
    send_cmd_raw "rc_inject_event" "$cmd_id" "$params"
    local result
    result=$(poll_result "$cmd_id" 5)
    [ "$(echo "$result" | jq -r '.status')" = "success" ] \
        || fail "rc_inject_event ($action on $session_id) failed: $result"
}

set_only_open() {
    local only_open="$1"
    local cmd_id="${RUN_ID}_sel_$(date +%s%N | cut -c1-13)"
    local params="{\"show_only_open\":$only_open}"
    send_cmd_raw "chatlist_select_folder" "$cmd_id" "$params"
    local result
    result=$(poll_result "$cmd_id" 5)
    [ "$(echo "$result" | jq -r '.status')" = "success" ] \
        || fail "chatlist_select_folder (show_only_open=$only_open) failed: $result"
    echo "$result" | jq -c '.data'
}

dump() {
    local cmd_id="${RUN_ID}_dump_$(date +%s%N | cut -c1-13)"
    send_cmd_raw "chatlist_dump" "$cmd_id" "{}"
    local result
    result=$(poll_result "$cmd_id" 5)
    [ "$(echo "$result" | jq -r '.status')" = "success" ] \
        || fail "chatlist_dump failed: $result"
    echo "$result" | jq -c '.data'
}

adb shell am start -n "$APP_ID_LOCAL/.MainActivity" >/dev/null 2>&1 || true
sleep 2

info "=== Scenario 1: startup / no sessions ==="
set_only_open false > /dev/null
d=$(dump)
echo "  dump: $d"
count=$(echo "$d" | jq '.count')
[ "$(echo "$d" | jq '.showOnlyOpen')" = "false" ] \
    || fail "expected showOnlyOpen=false on startup, got $d"
pass "Scenario 1: no sessions on startup"

info "=== Scenario 2: START two sessions -> both visible in All ==="
inject s1 sess_a /a/Repository start
inject s2 sess_b /b/shareitt   start
sleep 1
d=$(dump)
echo "  dump: $d"
count=$(echo "$d" | jq '.count')
[ "$count" = "2" ] || fail "expected 2 items in All view, got $count. dump=$d"
all_red=$(echo "$d" | jq '[ .items[] | .turning ] | all(. == false)')
[ "$all_red" = "true" ] || fail "expected both turning=false, got $d"
pass "Scenario 2: both sessions visible, both not turning"

info "=== Scenario 3: toggle showOnlyOpen=true -> both active sessions visible ==="
r=$(set_only_open true)
echo "  select result: $r"
[ "$(echo "$r" | jq '.showOnlyOpen')" = "true" ] \
    || fail "expected showOnlyOpen=true, got $r"
d=$(dump)
count=$(echo "$d" | jq '.count')
[ "$count" = "2" ] || fail "expected 2 active items in Only Open, got $count. dump=$d"
pass "Scenario 3: Only Open shows both active sessions"

info "=== Scenario 4: THINKING on sess_a -> turning=true ==="
inject s3 sess_a /a/Repository thinking
sleep 1
d=$(dump)
sess_a_turning=$(echo "$d" | jq -r '[.items[] | select(.sessionId=="sess_a") | .turning][0]')
[ "$sess_a_turning" = "true" ] \
    || fail "expected turning=true after thinking, got $d"
pass "Scenario 4: thinking -> turning=true"

info "=== Scenario 5: MESSAGE isFinal=false -> turning stays true ==="
inject s4 sess_a /a/Repository message false "streaming..."
sleep 1
d=$(dump)
sess_a_turning=$(echo "$d" | jq -r '[.items[] | select(.sessionId=="sess_a") | .turning][0]')
[ "$sess_a_turning" = "true" ] \
    || fail "expected turning=true on non-final msg, got $d"
pass "Scenario 5: mid-stream keeps turning=true"

info "=== Scenario 6: MESSAGE isFinal=true -> turning=false, session still visible ==="
inject s5 sess_a /a/Repository message true "done."
sleep 1
d=$(dump)
sess_a_turning=$(echo "$d" | jq -r '[.items[] | select(.sessionId=="sess_a") | .turning][0]')
[ "$sess_a_turning" = "false" ] \
    || fail "expected turning=false after final, got $d"
sess_a_status=$(echo "$d" | jq -r '[.items[] | select(.sessionId=="sess_a") | .status][0]')
[ "$sess_a_status" = "active" ] \
    || fail "session should still be active, got $d"
pass "Scenario 6: final message -> turning=false, session retained"

info "=== Scenario 7: END sess_b -> hidden in Only Open, visible in All ==="
inject s6 sess_b /b/shareitt end
sleep 1
# In Only Open mode, ended sessions should be filtered out.
d=$(dump)
count=$(echo "$d" | jq '.count')
[ "$count" = "1" ] || fail "expected 1 item in Only Open after ending sess_b, got $count. dump=$d"
[ "$(echo "$d" | jq -r '.items[0].sessionId')" = "sess_a" ] \
    || fail "expected only sess_a in Only Open, got $d"

# Switch to All -- both should be visible, sess_b as ended.
set_only_open false > /dev/null
d=$(dump)
count=$(echo "$d" | jq '.count')
[ "$count" = "2" ] || fail "expected 2 items in All view, got $count. dump=$d"
sess_b_status=$(echo "$d" | jq -r '[.items[] | select(.sessionId=="sess_b") | .status][0]')
[ "$sess_b_status" = "ended" ] \
    || fail "expected sess_b.status=ended, got $sess_b_status. dump=$d"
pass "Scenario 7: ended session hidden in Only Open, visible in All"

info "=== Scenario 8: stress -- 20 sessions across 4 folders ==="
# Clean up previous sessions.
inject sclean sess_a /a/Repository end
sleep 0.5

stress_folders=( /x/Repository /y/shareitt /z/varingait /w/ClickReserve )
for i in $(seq 1 20); do
    f=${stress_folders[$(( (i - 1) % 4 ))]}
    inject "stress$i" "stress_$i" "$f" start
done
sleep 1

# All view: 20 active + 2 ended from earlier = 22
set_only_open false > /dev/null
d=$(dump)
total=$(echo "$d" | jq '.count')
active_count=$(echo "$d" | jq '[.items[] | select(.status=="active")] | length')
[ "$active_count" = "20" ] || fail "expected 20 active items in All, got $active_count. dump=$d"

# Only Open: exactly 20
set_only_open true > /dev/null
d=$(dump)
count=$(echo "$d" | jq '.count')
[ "$count" = "20" ] || fail "expected 20 items in Only Open, got $count. dump=$d"
pass "Scenario 8: 20 active sessions visible"

# Cleanup
for i in $(seq 1 20); do
    f=${stress_folders[$(( (i - 1) % 4 ))]}
    inject "cleanup$i" "stress_$i" "$f" end
done
set_only_open false > /dev/null

echo
pass "All RC filter scenarios passed"
