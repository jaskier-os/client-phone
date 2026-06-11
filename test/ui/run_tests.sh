#!/usr/bin/env bash
# Run phone UI test suite via ADB + uiautomator2.
#
# Usage:
#   ./run_tests.sh                    # Run all tests
#   ./run_tests.sh test_todo.py       # Run only todo tests
#   ./run_tests.sh -k "toggle"        # Run tests matching "toggle"
#   ./run_tests.sh --clean            # Clean screenshots/reports before run
#
# Prerequisites:
#   - Phone connected via USB (adb devices shows YOUR_PHONE_SERIAL)
#   - pip install -r requirements.txt
#   - python3 -m uiautomator2 init --serial YOUR_PHONE_SERIAL
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCREENSHOTS_DIR="$SCRIPT_DIR/screenshots"
REPORTS_DIR="$SCRIPT_DIR/reports"
PHONE_SERIAL="YOUR_PHONE_SERIAL"
PHONE_PACKAGE="com.repository.listener"

# Parse args
CLEAN=false
PYTEST_ARGS=()
for arg in "$@"; do
    if [[ "$arg" == "--clean" ]]; then
        CLEAN=true
    else
        PYTEST_ARGS+=("$arg")
    fi
done

# Check device
if ! adb devices | grep -q "$PHONE_SERIAL"; then
    echo "ERROR: Phone not connected via ADB"
    echo "Expected device serial: $PHONE_SERIAL"
    exit 1
fi

# Clean if requested
if [[ "$CLEAN" == "true" ]]; then
    rm -rf "$SCREENSHOTS_DIR" "$REPORTS_DIR"
    echo "Cleaned screenshots and reports directories"
fi

mkdir -p "$SCREENSHOTS_DIR" "$REPORTS_DIR"

# Ensure app is in foreground
CURRENT_PKG=$(adb -s "$PHONE_SERIAL" shell dumpsys window | grep -oP 'mCurrentFocus.*?\K(com\.\S+?)/' | head -1 | tr -d '/' || true)
if [[ "$CURRENT_PKG" != "$PHONE_PACKAGE" ]]; then
    echo "Starting phone app..."
    adb -s "$PHONE_SERIAL" shell am start -n "$PHONE_PACKAGE/.MainActivity"
    sleep 2
fi

echo "Running phone UI tests..."
cd "$SCRIPT_DIR"
python3 -m pytest "${PYTEST_ARGS[@]}" "$SCRIPT_DIR"
