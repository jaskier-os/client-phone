#!/bin/bash
# Smoke test: verify Azure translation provider initializes on the phone.
#
# Sends start_translation with provider=azure, audio_source=system, en->ru.
# Tails logcat for the AzureTranslation tag for 30s, then issues stop_translation.
#
# This does NOT require real audio on the phone -- it just confirms the SDK loads,
# the recognizer constructs without NoClassDefFoundError, and Azure accepts the key
# (sessionStarted should fire once the WebSocket connects).
#
# Usage:
#   bash AI/clients/phone/test/adb/test_azure_translation.sh

set -u

PHONE_SERIAL="YOUR_PHONE_SERIAL"
PKG="com.repository.listener"
RX="${PKG}/.adb.AdbCommandReceiver"
ACTION="${PKG}.ADB_COMMAND"

CMD_ID_START="azure_smoke_start_$(date +%s)"
CMD_ID_STOP="azure_smoke_stop_$(date +%s)"

if ! adb -s "$PHONE_SERIAL" get-state >/dev/null 2>&1; then
    echo "Phone $PHONE_SERIAL not reachable via ADB. Aborting."
    exit 1
fi

echo "[1/4] Clearing logcat buffer"
adb -s "$PHONE_SERIAL" logcat -c

echo "[2/4] Sending start_translation (provider=azure, audio_source=system, en->ru)"
PARAMS='{"provider":"azure","audio_source":"system","from_language":"en","to_language":"ru","from_nllb":"eng_Latn","to_nllb":"rus_Cyrl","font_size":14}'
adb -s "$PHONE_SERIAL" shell am broadcast \
    -n "$RX" \
    -a "$ACTION" \
    --es type "start_translation" \
    --es command_id "$CMD_ID_START" \
    --es params "'$PARAMS'" 2>&1 | tail -3

echo "[3/4] Tailing logcat for AzureTranslation tag (30s)"
timeout 30 adb -s "$PHONE_SERIAL" logcat -v brief 2>&1 | grep -i -E "Azure|TranslationConfig|TranslationRecognizer|NoClassDefFoundError" &
TAIL_PID=$!
wait $TAIL_PID 2>/dev/null

echo "[4/4] Sending stop_translation"
adb -s "$PHONE_SERIAL" shell am broadcast \
    -n "$RX" \
    -a "$ACTION" \
    --es type "stop_translation" \
    --es command_id "$CMD_ID_STOP" \
    --es params "{}" 2>&1 | tail -3

echo "Done. Inspect output above for AzureTranslation log lines:"
echo "  - 'Starting Azure session ...'   -> provider branch reached"
echo "  - 'Azure recognizer started'     -> SDK loaded, recognizer constructed"
echo "  - 'Azure session started ...'    -> WebSocket up, key accepted"
echo "  - any NoClassDefFoundError       -> dependency missing"
