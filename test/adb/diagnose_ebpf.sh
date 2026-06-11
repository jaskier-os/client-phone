#!/bin/bash
# diagnose_ebpf.sh -- Diagnose eBPF ingress filtering on Android
# Run when socketRead0 blocks forever on WebSocket connections.
# Checks all eBPF maps, network policies, doze state, and MIUI restrictions.

set -euo pipefail

PKG="com.repository.listener"
WS_PORT_LOCAL_HEX="2711"   # 10001 decimal (local flavor)
WS_PORT_PROD_HEX="20FB"    # 8443 decimal (production flavor)
PORT_GREP="$WS_PORT_LOCAL_HEX\|$WS_PORT_PROD_HEX"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

section() { echo -e "\n${CYAN}=== $1 ===${NC}"; }
ok()      { echo -e "  ${GREEN}[OK]${NC} $1"; }
warn()    { echo -e "  ${YELLOW}[WARN]${NC} $1"; }
fail()    { echo -e "  ${RED}[FAIL]${NC} $1"; }
info()    { echo -e "  [INFO] $1"; }

# ---------- UID ----------
section "1. App UID"
UID_LINE=$(adb shell dumpsys package "$PKG" 2>/dev/null | grep "userId=" | head -1)
APP_UID=$(echo "$UID_LINE" | sed 's/.*userId=\([0-9]*\).*/\1/')
if [ -z "$APP_UID" ]; then
    fail "Could not find UID for $PKG. Is the app installed?"
    exit 1
fi
ok "Package: $PKG  UID: $APP_UID"

# ---------- PID ----------
section "2. Process State"
APP_PID=$(adb shell pidof "$PKG" 2>/dev/null || true)
if [ -z "$APP_PID" ]; then
    warn "App is not running. Start it first for full diagnostics."
else
    ok "PID: $APP_PID"
    CGROUP=$(adb shell cat "/proc/$APP_PID/cgroup" 2>/dev/null || echo "unreadable")
    info "Cgroup: $CGROUP"
fi

# ---------- Network Policy ----------
section "3. Network Policy (cmd netpolicy)"
UID_POLICY=$(adb shell cmd netpolicy get uid-policy "$APP_UID" 2>/dev/null || echo "error")
info "UID policy: $UID_POLICY"
case "$UID_POLICY" in
    *NONE*|*none*|*0*)     ok "No network restriction set" ;;
    *REJECT_METERED*|*1*)  fail "REJECT_METERED -- blocked on metered networks" ;;
    *ALLOW_METERED*|*2*)   ok "ALLOW_METERED -- explicitly allowed" ;;
    *REJECT_ALL*|*3*)      fail "REJECT_ALL -- blocked on ALL networks" ;;
    *)                     warn "Unknown policy value" ;;
esac

BG_RESTRICT=$(adb shell cmd netpolicy get restrict-background 2>/dev/null || echo "error")
info "Global background restrict: $BG_RESTRICT"
if echo "$BG_RESTRICT" | grep -qi "enabled\|true"; then
    BG_WHITELIST=$(adb shell cmd netpolicy list restrict-background-whitelist 2>/dev/null || echo "")
    if echo "$BG_WHITELIST" | grep -q "$APP_UID"; then
        ok "App UID $APP_UID is in background whitelist"
    else
        fail "Background data is restricted globally and app is NOT in whitelist"
    fi
else
    ok "Background data restriction is disabled globally"
fi

# ---------- Device Idle / Doze ----------
section "4. Device Idle (Doze)"
IDLE_STATE=$(adb shell dumpsys deviceidle get deep 2>/dev/null || echo "error")
info "Deep doze state: $IDLE_STATE"
LIGHT_STATE=$(adb shell dumpsys deviceidle get light 2>/dev/null || echo "error")
info "Light doze state: $LIGHT_STATE"

WHITELIST=$(adb shell dumpsys deviceidle whitelist 2>/dev/null || echo "")
if echo "$WHITELIST" | grep -q "$PKG"; then
    ok "App is in device idle whitelist"
else
    fail "App is NOT in device idle whitelist"
    warn "Fix: adb shell dumpsys deviceidle whitelist +$PKG"
fi

# ---------- Battery Optimization ----------
section "5. Battery Optimization"
BATTERY_OPT=$(adb shell dumpsys deviceidle whitelist 2>/dev/null | grep "$PKG" || echo "not_found")
if [ "$BATTERY_OPT" != "not_found" ]; then
    ok "Battery optimization is disabled (whitelisted)"
else
    fail "Battery optimization may be active for this app"
fi

# ---------- eBPF Maps ----------
section "6. eBPF Map State"
TRAFFIC_CTRL=$(adb shell dumpsys connectivity trafficcontroller 2>/dev/null || echo "")
if [ -n "$TRAFFIC_CTRL" ]; then
    info "TrafficController dump available"
    echo "$TRAFFIC_CTRL" | head -30
else
    warn "Could not dump trafficcontroller"
fi

# Try direct BPF map access
BPF_LS=$(adb shell ls /sys/fs/bpf/netd_shared/ 2>/dev/null || echo "no_access")
if [ "$BPF_LS" != "no_access" ]; then
    info "BPF maps found in /sys/fs/bpf/netd_shared/:"
    echo "$BPF_LS" | grep -i "uid_owner\|configuration\|cookie" | head -10
fi

BPFTOOL=$(adb shell which bpftool 2>/dev/null || echo "")
if [ -n "$BPFTOOL" ]; then
    info "bpftool available -- dumping uid_owner_map for UID $APP_UID:"
    adb shell bpftool map dump pinned /sys/fs/bpf/netd_shared/map_netd_uid_owner_map 2>/dev/null | grep -A2 "$APP_UID" || warn "UID not found in map"
    info "Configuration map:"
    adb shell bpftool map dump pinned /sys/fs/bpf/netd_shared/map_netd_configuration_map 2>/dev/null | head -10
else
    warn "bpftool not available on device (normal for stock ROM)"
fi

# ---------- Socket Buffer State ----------
section "7. Socket Buffer State (rx_queue check)"
if [ -n "$APP_PID" ]; then
    TCP6=$(adb shell cat /proc/net/tcp6 2>/dev/null || echo "")
    WS_SOCKETS=$(echo "$TCP6" | grep -i "$PORT_GREP" || echo "")
    if [ -n "$WS_SOCKETS" ]; then
        info "WebSocket TCP connections found:"
        echo "  sl  local_address                         remote_address                        st tx_queue:rx_queue"
        echo "$WS_SOCKETS" | while read -r line; do
            STATE=$(echo "$line" | awk '{print $4}')
            QUEUES=$(echo "$line" | awk '{print $5}')
            RX=$(echo "$QUEUES" | cut -d: -f2)
            echo "  $line"
            if [ "$STATE" = "01" ]; then
                if [ "$RX" = "00000000" ]; then
                    echo -e "    ${RED}--> rx_queue=0 on ESTABLISHED socket. If server is sending data, eBPF is DROPPING ingress.${NC}"
                else
                    echo -e "    ${GREEN}--> rx_queue=$RX -- data is reaching the socket buffer.${NC}"
                fi
            fi
        done
    else
        warn "No WebSocket connections found on ports 10001/8443"
        info "Is the WebSocket connected? Check OrchestratorClient state."
    fi
else
    warn "App not running, cannot check socket state"
fi

# ---------- MIUI / HyperOS ----------
section "8. MIUI / HyperOS Specific"
MIUI_VER=$(adb shell getprop ro.miui.ui.version.name 2>/dev/null || echo "unknown")
HYPER_VER=$(adb shell getprop ro.mi.os.version.name 2>/dev/null || echo "unknown")
info "MIUI version: $MIUI_VER"
info "HyperOS version: $HYPER_VER"

# PowerKeeper
PK_ENABLED=$(adb shell pm list packages -e 2>/dev/null | grep "com.miui.powerkeeper" || echo "")
PK_DISABLED=$(adb shell pm list packages -d 2>/dev/null | grep "com.miui.powerkeeper" || echo "")
if [ -n "$PK_ENABLED" ]; then
    warn "PowerKeeper is ENABLED -- this is the most likely culprit on POCO"
    warn "Fix: adb shell pm disable-user --user 0 com.miui.powerkeeper"
elif [ -n "$PK_DISABLED" ]; then
    ok "PowerKeeper is DISABLED"
else
    info "PowerKeeper not found (not a Xiaomi device?)"
fi

# Network Assistant
NA_ENABLED=$(adb shell pm list packages -e 2>/dev/null | grep "com.miui.networkassistant" || echo "")
if [ -n "$NA_ENABLED" ]; then
    warn "Network Assistant is enabled -- can restrict per-app network"
    info "Check: Settings > Apps > Manage apps > $PKG > Data usage"
else
    ok "Network Assistant not found or disabled"
fi

# MIUI Cloud Control
CLOUD=$(adb shell settings get global miui_cloud_control 2>/dev/null || echo "null")
info "MIUI cloud control: $CLOUD"

# ---------- Summary ----------
section "SUMMARY"
echo ""
echo "If the WebSocket is stuck (socketRead0 blocking) and Step 7 shows rx_queue=0"
echo "on an ESTABLISHED connection while the server confirms sending data, then"
echo "Android's eBPF cgroup_skb/ingress filter is silently dropping packets."
echo ""
echo "Quick fix (run all of these):"
echo ""
echo "  adb shell dumpsys deviceidle whitelist +$PKG"
echo "  adb shell cmd netpolicy add restrict-background-whitelist $APP_UID"
echo "  adb shell cmd netpolicy set uid-policy $APP_UID allow-metered"
echo "  adb shell dumpsys deviceidle disable"
echo "  adb shell settings put global low_power 0"
echo "  adb shell pm disable-user --user 0 com.miui.powerkeeper"
echo "  adb shell pm disable-user --user 0 com.miui.networkassistant"
echo ""
echo "Then reconnect the WebSocket and test."
echo "If it works, re-enable services one at a time to find the specific culprit."
