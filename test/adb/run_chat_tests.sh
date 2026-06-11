#!/usr/bin/env bash
# Run all chat-related ADB tests
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'

passed=0
failed=0
skipped=0

run_test() {
    local test_file="$1"
    local test_name
    test_name=$(basename "$test_file" .sh)
    echo ""
    echo "--- Running: $test_name ---"

    set +e
    output=$(bash "$test_file" 2>&1)
    exit_code=$?
    set -e

    echo "$output"

    if echo "$output" | grep -q "^SKIP:"; then
        skipped=$((skipped + 1))
    elif [ $exit_code -eq 0 ]; then
        passed=$((passed + 1))
    else
        failed=$((failed + 1))
    fi
}

echo "=== Chat Test Suite ==="

# Run tests in order: simple first, complex last
run_test "$SCRIPT_DIR/test_chat_status.sh"
run_test "$SCRIPT_DIR/test_chat_list.sh"
run_test "$SCRIPT_DIR/test_chat_get.sh"
run_test "$SCRIPT_DIR/test_chat_send.sh"
run_test "$SCRIPT_DIR/test_chat_flow.sh"

echo ""
echo "=== Results ==="
echo -e "  ${GREEN}Passed:  $passed${NC}"
echo -e "  ${RED}Failed:  $failed${NC}"
echo -e "  ${YELLOW}Skipped: $skipped${NC}"
echo ""

if [ $failed -gt 0 ]; then
    echo -e "${RED}Some tests failed!${NC}"
    exit 1
else
    echo -e "${GREEN}All tests passed!${NC}"
fi
