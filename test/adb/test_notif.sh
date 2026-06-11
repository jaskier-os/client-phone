#!/usr/bin/env bash
# Test Telegram notification relay to glasses via ADB command.
# Sends a global toast to glasses through CXR-M SDK.
#
# Usage:
#   bash test_notif.sh                              # default test message
#   bash test_notif.sh "Custom message"             # custom text, default sender
#   bash test_notif.sh "Message" "John"             # custom text + sender
#   bash test_notif.sh "Message" "John" "Work Chat" # custom text + sender + group

set -euo pipefail

TEXT="${1:-This is a test notification from ADB}"
SENDER="${2:-Telegram Test}"
CHAT="${3:-}"
CMD_ID="notif_$(date +%s)"

echo "Sending test notification to glasses..."
echo "  Sender: $SENDER"
echo "  Text:   $TEXT"
[ -n "$CHAT" ] && echo "  Chat:   $CHAT"

PARAMS="{\"sender\":\"$SENDER\",\"text\":\"$TEXT\",\"chat\":\"$CHAT\"}"

adb shell am broadcast \
  -n com.repository.listener/.adb.AdbCommandReceiver \
  -a com.repository.listener.ADB_COMMAND \
  --es type "test_notif" \
  --es command_id "$CMD_ID" \
  --es params "$PARAMS"

sleep 1

echo ""
echo "Result:"
adb shell run-as com.repository.listener cat "files/adb_results/$CMD_ID.json" 2>/dev/null || echo "(no result file yet -- glasses may not be connected)"
