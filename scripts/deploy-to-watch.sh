#!/bin/bash
# Deploy the Wear OS app to the Galaxy Watch.
#
# Usage: bash deploy-to-watch.sh [--cleanup]
#
# SAFETY. This script can install an APK whose applicationId is identical to the
# phone app's (the Wear Data Layer requires that). An install aimed at the wrong
# device would therefore overwrite the phone app and destroy user data. Two
# INDEPENDENT hard guards run before any install, and both must pass:
#   1. The serial must be a network ip:port serial. USB serials cannot match.
#   2. The target must report android.hardware.type.watch.
# It builds :wear:assembleDebug only and never invokes an :app task.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PHONE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APK="$PHONE_DIR/wear/build/outputs/apk/debug/wear-debug.apk"
PACKAGE="com.repository.listener"
ACTIVITY="com.repository.listener.wear.ScrollRemoteActivity"
COMPLICATION="com.repository.listener.wear.LinkComplicationService"
COMPLICATION_ACTION="android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST"
SERIAL_FILE="$HOME/.config/repository/watch-serial"

CLEANUP=0
[ "${1:-}" = "--cleanup" ] && CLEANUP=1

# --- Resolve the watch serial ---
if [ -n "${WATCH_SERIAL:-}" ]; then
    SERIAL="$WATCH_SERIAL"
elif [ -f "$SERIAL_FILE" ]; then
    SERIAL="$(cat "$SERIAL_FILE" | tr -d ' \r\n')"
else
    SERIAL="$(adb devices | grep -E '^[0-9]{1,3}(\.[0-9]{1,3}){3}:[0-9]+[[:space:]]' | head -1 | awk '{print $1}')"
fi

if [ -z "$SERIAL" ]; then
    echo "No watch serial. Set WATCH_SERIAL, write $SERIAL_FILE, or connect the watch."
    exit 1
fi

# --- GUARD 1: the serial must be a network ip:port serial ---
if ! echo "$SERIAL" | grep -qE '^[0-9]{1,3}(\.[0-9]{1,3}){3}:[0-9]+$'; then
    echo "REFUSING: '$SERIAL' is not a network ip:port serial."
    echo "The watch is reached over wireless debugging; a USB serial here would"
    echo "mean the phone or the glasses, and this APK shares the phone's"
    echo "applicationId. Aborting before any install."
    exit 1
fi

# Reconnect if the socket dropped (screen-off, reboot, or a port change).
if ! adb devices | grep -q "^${SERIAL}[[:space:]]*device$"; then
    echo "Watch not connected; trying adb connect $SERIAL ..."
    adb connect "$SERIAL" >/dev/null 2>&1
    sleep 2
fi

if ! adb devices | grep -q "^${SERIAL}[[:space:]]*device$"; then
    echo "Cannot reach the watch at $SERIAL."
    echo "The connection port is reassigned on every reboot / wireless-debugging"
    echo "toggle. Re-read it on the watch under Developer options > Wireless"
    echo "debugging, then run: adb connect <ip>:<port>"
    exit 1
fi

# --- GUARD 2: the target must actually be a watch ---
if ! adb -s "$SERIAL" shell pm list features 2>/dev/null | grep -q 'android.hardware.type.watch'; then
    echo "REFUSING: $SERIAL does not report android.hardware.type.watch."
    echo "This APK shares the phone app's applicationId; installing it on a"
    echo "non-watch device would overwrite the phone app. Aborting."
    exit 1
fi

MODEL="$(adb -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r\n')"
echo "Target: $SERIAL ($MODEL)"

# --- Cleanup mode ---
if [ "$CLEANUP" = "1" ]; then
    echo "Reverting the development doze whitelist ..."
    adb -s "$SERIAL" shell dumpsys deviceidle whitelist "-$PACKAGE" 2>/dev/null | sed 's/^/  /'
    echo
    echo "Doze whitelist reverted. Remaining teardown is manual on the watch:"
    echo "  Settings > Developer options > Wireless debugging > OFF"
    echo "  Developer options > Revoke debug authorizations"
    echo "  Developer options > ADB debugging > OFF, then Developer options OFF"
    echo "Note: the installed APK still holds the HMAC key. To remove it:"
    echo "  adb -s $SERIAL uninstall $PACKAGE   # safe: watch only, guarded above"
    exit 0
fi

# --- Build (never an :app task) ---
echo "Building :wear:assembleDebug ..."
if ! "$PHONE_DIR/gradlew" -p "$PHONE_DIR" :wear:assembleDebug; then
    echo "BUILD FAILED."
    exit 1
fi

if [ ! -f "$APK" ]; then
    echo "APK not found at $APK"
    exit 1
fi

# --- Verify the signing certificate matches the phone app ---
# A mismatch silently kills the Data Layer: nodes still resolve and sendMessage
# just no-ops. Catch it here rather than debugging a dead link later.
APKSIGNER="$(ls -d "$HOME"/Android/Sdk/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
PHONE_APK="$PHONE_DIR/app/build/outputs/apk/production/debug/app-production-debug.apk"
if [ -n "$APKSIGNER" ] && [ -f "$PHONE_APK" ]; then
    W="$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | grep -i 'certificate SHA-256' | head -1 | awk '{print $NF}')"
    P="$("$APKSIGNER" verify --print-certs "$PHONE_APK" 2>/dev/null | grep -i 'certificate SHA-256' | head -1 | awk '{print $NF}')"
    if [ -n "$W" ] && [ -n "$P" ] && [ "$W" != "$P" ]; then
        echo "REFUSING: the wear APK's signing certificate does not match the phone app."
        echo "  wear:  $W"
        echo "  phone: $P"
        echo "The Data Layer would silently drop every message. Do NOT fix this by"
        echo "reinstalling the phone app -- that requires an uninstall and wipes"
        echo "user data. Investigate the keystore instead."
        exit 1
    fi
    echo "Signing certificate matches the phone app."
fi

# --- Install ---
echo "Installing ..."
if ! adb -s "$SERIAL" install -r "$APK"; then
    echo "Install failed; retrying once through a reconnect ..."
    adb disconnect "$SERIAL" >/dev/null 2>&1
    adb connect "$SERIAL" >/dev/null 2>&1
    sleep 2
    if ! adb -s "$SERIAL" install -r "$APK"; then
        echo "INSTALL FAILED."
        exit 1
    fi
fi

# --- Whitelist from doze for the development session ---
adb -s "$SERIAL" shell dumpsys deviceidle whitelist "+$PACKAGE" >/dev/null 2>&1

# --- Report complication enumeration ---
#
# A complication data source cannot be placed over adb: the watch-face editor
# owns the slot assignment and requires the user to pick it. What CAN be checked
# without touching the watch is that the platform enumerates the provider at all,
# which is the precondition for it being offered in the picker.
echo "Checking the complication provider is enumerated ..."
if adb -s "$SERIAL" shell "cmd package query-services -a $COMPLICATION_ACTION" 2>/dev/null \
    | grep -q "$COMPLICATION"; then
    echo "  OK: $PACKAGE/$COMPLICATION is enumerated."
    echo "  Place it on the watch: long-press the watch face, tap the slot you want,"
    echo "  scroll the provider list to 'Glasses Remote'."
else
    echo "  NOT ENUMERATED. The picker will not offer it. Check the manifest"
    echo "  service label, icon, permission and SUPPORTED_TYPES."
fi

# --- Launch ---
echo "Launching ..."
adb -s "$SERIAL" shell am force-stop "$PACKAGE"
sleep 1
adb -s "$SERIAL" shell am start -n "$PACKAGE/$ACTIVITY" 2>&1 | sed 's/^/  /'

echo
echo "Deployed to $SERIAL ($MODEL)."
