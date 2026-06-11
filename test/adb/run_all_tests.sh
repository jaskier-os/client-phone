#!/usr/bin/env bash
# Run all ADB command tests
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'

PASSED=0
FAILED=0
SKIPPED=0

run_test() {
    local test_file="$1"
    local test_name
    test_name=$(basename "$test_file" .sh)

    echo ""
    echo "--- Running: $test_name ---"

    local output
    local exit_code
    output=$(bash "$test_file" 2>&1) && exit_code=0 || exit_code=$?

    echo "$output"

    # Strip ANSI color codes before checking for SKIP
    if echo "$output" | sed 's/\x1b\[[0-9;]*m//g' | grep -q "^SKIP:"; then
        SKIPPED=$((SKIPPED + 1))
    elif [ "$exit_code" -eq 0 ]; then
        PASSED=$((PASSED + 1))
    else
        FAILED=$((FAILED + 1))
    fi
}

echo "========================================"
echo "  ADB Command Test Suite"
echo "========================================"

# Run tests in order (skip test_helpers.sh -- it's a library, not a test)
for test_file in "$SCRIPT_DIR"/test_*.sh; do
    [ -f "$test_file" ] || continue
    [[ "$(basename "$test_file")" == "test_helpers.sh" ]] && continue
    run_test "$test_file"
done

echo ""
echo "========================================"
echo -e "  Results: ${GREEN}${PASSED} passed${NC}, ${RED}${FAILED} failed${NC}, ${YELLOW}${SKIPPED} skipped${NC}"
echo "========================================"

[ "$FAILED" -eq 0 ] && exit 0 || exit 1
