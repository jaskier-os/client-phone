#!/bin/bash
# Mirror phone screen via scrcpy.
# Usage: bash scrcpy-phone.sh

PHONE_SERIAL=""
while IFS= read -r line; do
    serial=$(echo "$line" | awk '$2=="device" {print $1}')
    [ -z "$serial" ] && continue
    model=$(adb -s "$serial" shell getprop ro.product.model </dev/null 2>/dev/null | tr -d '\r')
    if [ "$model" != "RG-glasses" ]; then
        PHONE_SERIAL="$serial"
        break
    fi
done < <(adb devices 2>/dev/null)

if [ -z "$PHONE_SERIAL" ]; then
    echo "Phone not found via ADB."
    exit 1
fi

echo "Starting scrcpy on phone ($PHONE_SERIAL)..."
exec scrcpy -s "$PHONE_SERIAL" --max-size 1280 --no-audio
