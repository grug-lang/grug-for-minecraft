#!/usr/bin/env bash
# report-native-abort.sh <log-file> [exit-code]
#
# Explains a game process that died inside the grug VM instead of failing a test. A Rust panic in
# grug-rs has to unwind out of an extern "C" frame, which cannot unwind, so the panic handler
# aborts the process (SIGABRT, exit 134). Nothing in Gradle's own output says so: the job fails
# with "finished with non-zero exit value 134", there is no [GRUG CI] FAIL line, and the panic text
# only appears among tens of thousands of lines of game log unless you search for it. That is a
# different failure shape from every other test failure, and it is easy to mistake for an
# infrastructure problem.
#
# Prints the report on stdout and exits 0 when the log shows such an abort. Prints nothing and
# exits 1 when it does not, so the caller can fall through to its own reporting. A missing log
# file also exits 1, because "no log" is not an abort.

set -uo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo "Usage: $0 <log-file> [exit-code]" >&2
  exit 2
fi

LOG_FILE="$1"
EXIT_CODE="${2:-}"

if [ ! -f "$LOG_FILE" ]; then
  exit 1
fi

# Gradle's own log has no timestamps, but the CI console log and any redirected copy of one does,
# and a leading ISO timestamp on every quoted line buries the part a reader is looking for.
strip_timestamp() {
  sed -E "s/^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?Z //"
}

# Rust prints the panic location, the source line and the colon on one line ("... panicked at
# <file>:<line>:<col>:") and the panic message on the next, so take the marker line plus the
# message after it. Every Rust panic location ends in .rs:<line>:<col>, and matching that rather
# than the bare words keeps a mod that logs the phrase from being reported as an abort.
PANIC_RE='panicked at .*\.rs:[0-9]+:[0-9]+'
PANIC_LINE=$(grep -m1 -E "$PANIC_RE" "$LOG_FILE" | sed "s/^[[:space:]]*//" | strip_timestamp)
PANIC_MESSAGE=$(awk '/panicked at /{ found = 1; next } found && NF { print; exit }' "$LOG_FILE" \
  | strip_timestamp)
PANIC_LOCATION=$(printf "%s\n" "$PANIC_LINE" | sed -n "s/.*panicked at \(.*\):$/\1/p")

# A panic that could not unwind names the abort it caused; an abort from anywhere else (a glibc
# abort, a JVM crash) is reported without one.
ABORT_LINE=$(grep -m1 "non-unwinding panic" "$LOG_FILE" | sed "s/^[[:space:]]*//" | strip_timestamp)

# 134 is 128 + SIGABRT, which is how both the game and Gradle report a native abort.
if [ -z "$PANIC_LINE" ] && [ "${EXIT_CODE:-}" != "134" ]; then
  exit 1
fi

if [ -n "$PANIC_LINE" ]; then
  echo "==> The game aborted inside the grug VM: a Rust panic in grug-rs killed the JVM."
  echo "==> A panic cannot unwind out of an extern \"C\" frame, so this aborted the whole process"
  echo "==> rather than failing a test, which is why no [GRUG CI] FAIL line was printed."
  echo "==> panicked at ${PANIC_LOCATION:-an unknown location}"
  if [ -n "$PANIC_MESSAGE" ]; then
    echo "==> panic message: ${PANIC_MESSAGE}"
  fi
else
  echo "==> The game aborted with exit code 134 and printed no Rust panic, so this is not the"
  echo "==> grug VM panic: treat it as a native crash in the game, the JVM or the adapter."
fi
if [ -n "$ABORT_LINE" ]; then
  echo "==> ${ABORT_LINE}"
fi

# The panic itself carries a backtrace, and the frames worth reading are the grug-rs ones: they say
# which grug code path reached the borrow. They are only there when RUST_BACKTRACE is set, which
# run-loader.sh exports for that reason, and only for the panic that actually failed (the abort
# that follows unwinds nothing, so its backtrace is JNI frames all the way down).
GRUG_FRAMES=$(grep -E "gruggers::|grug_call_export_fn" "$LOG_FILE" \
  | head -n 12 \
  | strip_timestamp \
  | sed -E "s/^[[:space:]]*[0-9]+:[[:space:]]+0x[0-9a-f]+ - //" \
  | sed "s/^[[:space:]]*//")
if [ -n "$GRUG_FRAMES" ]; then
  echo "==> grug-rs frames from the backtrace:"
  printf '%s\n' "$GRUG_FRAMES" | sed "s/^/==>   /"
fi

if [ -n "$EXIT_CODE" ]; then
  echo "==> The process exited with code ${EXIT_CODE}."
fi

# Which test was running is the next most useful thing in the log, since the abort lands between
# two of the runner's own log lines rather than inside either of them.
LAST_PROGRESS=$(grep "\[GRUG CI\]" "$LOG_FILE" | tail -n1 | strip_timestamp)
if [ -n "$LAST_PROGRESS" ]; then
  echo "==> Last test progress: ${LAST_PROGRESS}"
else
  echo "==> The log holds no [GRUG CI] progress line, so the abort predates the test run."
fi

# A JVM crash also drops an hs_err_pid file next to the log; an abort inside the native adapter
# does not. Point at it either way so the next occurrence does not have to be re-derived.
RUN_DIR=$(dirname "$LOG_FILE")
crash_reports=("$RUN_DIR"/hs_err_pid*.log)
if [ -e "${crash_reports[0]}" ]; then
  echo "==> The JVM also wrote a crash report: ${crash_reports[*]}"
fi

echo "==> The panic itself is in grug-lang/grug-rs; this repository only sees the abort."
echo "==> Tracked as grug-lang/grug-for-minecraft#123, whose report carries the analysis."
echo "==> Full log: ${LOG_FILE}"
