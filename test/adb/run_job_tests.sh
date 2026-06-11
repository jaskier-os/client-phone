#!/usr/bin/env bash
# Run all job ADB tests in order
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

    if echo "$output" | sed 's/\x1b\[[0-9;]*m//g' | grep -q "^SKIP:"; then
        SKIPPED=$((SKIPPED + 1))
    elif [ "$exit_code" -eq 0 ]; then
        PASSED=$((PASSED + 1))
    else
        FAILED=$((FAILED + 1))
    fi
}

echo "========================================"
echo "  Job ADB Test Suite"
echo "========================================"

run_test "$SCRIPT_DIR/test_job_list.sh"
run_test "$SCRIPT_DIR/test_job_create.sh"
run_test "$SCRIPT_DIR/test_job_delete.sh"

echo ""
echo "========================================"
echo -e "  Results: ${GREEN}${PASSED} passed${NC}, ${RED}${FAILED} failed${NC}, ${YELLOW}${SKIPPED} skipped${NC}"
echo "========================================"

[ "$FAILED" -eq 0 ] && exit 0 || exit 1
