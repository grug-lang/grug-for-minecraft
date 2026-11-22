#!/usr/bin/env bash
# The smoketest job's steps (#1589), runnable locally: builds the host twice, once with
# AddressSanitizer and UBSan and once with gcov instrumentation, runs every mods/coverage/ logic
# test on each, and prints the contract, line and branch coverage counts. Advisory by default.
#
#   GRUG_RS=<grug-rs checkout holding target/release/libgruggers.a> .github/smoketest/run.sh
#
# Switches, each off by default until the host has its core (#1586), on only when set to exactly
# `true` (so `false`, `0` and empty are off):
#   SMOKETEST_ASSERT_CONTRACT   fail when a mod_api.json entry has no implementation
#   SMOKETEST_ASSERT_COVERAGE   fail below 100% line or branch coverage of smoketest.c (see line_coverage.py)
#   SMOKETEST_ASSERT_TESTS      fail when a test fails, not only on a sanitizer error or a crash
# Nothing here is cached: a run takes about a second.
set -u
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
export GRUG_RS=${GRUG_RS:?set GRUG_RS to a grug-rs checkout with target/release/libgruggers.a}
status=0

tests=$(cd "$root/mods/coverage" && ls tests/*-Test.grug | sed 's|^|coverage/|')

# Sanitizer findings abort with exit code 99, which a test failure (exit 1) never uses.
export ASAN_OPTIONS="exitcode=99:detect_leaks=1"
export UBSAN_OPTIONS="exitcode=99:print_stacktrace=1"
export LSAN_OPTIONS="exitcode=99:suppressions=$here/lsan.supp"

run() { # <binary> <label>
    # shellcheck disable=SC2086
    "$1" "$root" $tests >"$build/$2.log" 2>&1
    local rc=$?
    local pass fail
    pass=$(grep -c '^PASS' "$build/$2.log")
    fail=$(grep -c '^FAIL' "$build/$2.log")
    echo "$2: $pass tests passed, $fail failed (exit $rc)"
    if [ $((pass + fail)) -eq 0 ]; then
        echo "::error::the $2 run printed no PASS or FAIL line, so the host did not start (exit $rc)"
        tail -60 "$build/$2.log"
        status=1
    elif [ "$rc" -gt 1 ]; then
        echo "::error::the $2 run crashed or a sanitizer found something (exit $rc)"
        tail -60 "$build/$2.log"
        status=1
    elif [ "$rc" -eq 1 ] && [ "${SMOKETEST_ASSERT_TESTS:-}" = true ]; then
        grep '^FAIL' "$build/$2.log" | head -20
        status=1
    fi
}

echo "== AddressSanitizer and UBSan =="
make -C "$here" OUT="$build/asan" SAN="-fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer" >/dev/null || exit 1
run "$build/asan" sanitizers

echo "== selftest.c: C tests of the pure helpers, with known answers from a real JDK =="
make -C "$here" SELFTEST="$build/selftest" "$build/selftest" SAN="-fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer" >/dev/null || exit 1
"$build/selftest" || status=1

echo "== line and branch coverage of smoketest.c, from the mods/coverage/ tests =="
make -C "$here" OUT="$build/cov" CFLAGS="-O0 -g -Wall -Wextra -std=gnu11" SAN="--coverage" >/dev/null || exit 1
run "$build/cov" coverage
(cd "$build" && gcov --branch-probabilities --json-format --stdout cov-smoketest.gcda >gcov.json 2>/dev/null)
cov_args=()
[ "${SMOKETEST_ASSERT_COVERAGE:-}" = true ] && cov_args=(--assert)
python3 "$here/line_coverage.py" "$build/gcov.json" "$here/smoketest.c" "${cov_args[@]}" || status=1

echo "== contract: every mod_api.json entry needs an implementation =="
"$build/asan" --contract | tail -1
if [ "${SMOKETEST_ASSERT_CONTRACT:-}" = true ]; then
    "$build/asan" --contract >/dev/null || status=1
fi
exit $status
