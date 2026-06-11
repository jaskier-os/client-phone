#!/bin/bash
set -e

# Deploy phone app: build production debug, install, force restart.
# Usage: bash deploy-to-phone.sh

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PHONE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APK="$PHONE_DIR/app/build/outputs/apk/production/debug/app-production-debug.apk"
PACKAGE="com.repository.listener"
ACTIVITY=".MainActivity"

# --- Build ---
echo "Building production debug..."
BUILD_LOG=$(mktemp)
if ! "$PHONE_DIR/gradlew" -p "$PHONE_DIR" assembleProductionDebug > "$BUILD_LOG" 2>&1; then
    echo "BUILD FAILED. Log:"
    cat "$BUILD_LOG"
    rm -f "$BUILD_LOG"
    exit 1
fi
rm -f "$BUILD_LOG"
echo "Build OK."

if [ ! -f "$APK" ]; then
    echo "APK not found after build: $APK"
    exit 1
fi

# --- Find phone device ---
if [ -n "$ANDROID_SERIAL" ]; then
    PHONE_SERIAL="$ANDROID_SERIAL"
else
    # Skip glasses (RG_glasses model) and network devices, pick the phone
    PHONE_SERIAL=$(adb devices -l | grep -v "^$" | grep -v "List of" | grep -v "192\.168\." | grep -v "model:RG_glasses" | head -1 | awk '{print $1}')
fi

if [ -z "$PHONE_SERIAL" ]; then
    echo "No phone device found via ADB."
    exit 1
fi

echo "Device: $PHONE_SERIAL"

# --- Snapshot auto-rotate (MIUI/HyperOS resets this on reinstall) ---
ROT_BEFORE=$(adb -s "$PHONE_SERIAL" shell settings get system accelerometer_rotation 2>/dev/null | tr -d '\r\n')
if [ -n "$ROT_BEFORE" ] && [ "$ROT_BEFORE" != "null" ]; then
    echo "Auto-rotate before install: $ROT_BEFORE"
else
    ROT_BEFORE=""
fi

# --- Install ---
echo "Installing..."
if ! adb -s "$PHONE_SERIAL" install -r "$APK"; then
    echo "INSTALL FAILED."
    exit 1
fi

# --- Restore auto-rotate if it changed ---
if [ -n "$ROT_BEFORE" ]; then
    ROT_AFTER=$(adb -s "$PHONE_SERIAL" shell settings get system accelerometer_rotation 2>/dev/null | tr -d '\r\n')
    if [ "$ROT_AFTER" != "$ROT_BEFORE" ]; then
        echo "Auto-rotate changed ($ROT_BEFORE -> $ROT_AFTER), restoring to $ROT_BEFORE..."
        adb -s "$PHONE_SERIAL" shell settings put system accelerometer_rotation "$ROT_BEFORE" 2>/dev/null || \
            echo "WARNING: failed to restore accelerometer_rotation"
    fi
fi

# --- Restart ---
echo "Restarting $PACKAGE ..."
adb -s "$PHONE_SERIAL" shell am force-stop "$PACKAGE"
sleep 1
REMAINING=$(adb -s "$PHONE_SERIAL" shell pidof "$PACKAGE" 2>/dev/null || true)
if [ -n "$REMAINING" ]; then
    echo "Force-stop incomplete (PIDs: $REMAINING), killing..."
    adb -s "$PHONE_SERIAL" shell "kill -9 $REMAINING" 2>/dev/null || true
    sleep 1
fi
adb -s "$PHONE_SERIAL" shell am start -n "${PACKAGE}/${ACTIVITY}"

echo "Waiting 10s for services to initialize ..."
sleep 10

# --- Grant notification listener permission if not already allowed (MIUI/POCO revokes it on update) ---
ALLOWED=$(adb -s "$PHONE_SERIAL" shell dumpsys notification 2>/dev/null | grep "Allowed notification listeners" -A1 || true)
LISTENER="$PACKAGE/com.repository.listener.notification.TelegramNotificationListener"
if echo "$ALLOWED" | grep -q "$LISTENER"; then
    echo "Notification listener: already allowed."
else
    echo "Granting notification listener permission..."
    adb -s "$PHONE_SERIAL" shell cmd notification allow_listener "$LISTENER" 2>/dev/null || true
    sleep 2
    # Verify
    VERIFY=$(adb -s "$PHONE_SERIAL" shell dumpsys notification 2>/dev/null | grep "Allowed notification listeners" -A1 || true)
    if echo "$VERIFY" | grep -q "$LISTENER"; then
        echo "Notification listener: granted OK."
    else
        echo "WARNING: Could not grant notification listener. Enable manually in Settings > Notifications > Notification access."
    fi
fi

echo "Done."
