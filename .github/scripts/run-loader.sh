#!/usr/bin/env bash
# scripts/ci/run-loader.sh <loader-dir-name> <build|run>

set -uo pipefail

if [ "$#" -ne 2 ] || { [ "$2" != build ] && [ "$2" != run ]; }; then
  echo "Usage: $0 <loader-dir-name> <build|run>" >&2
  exit 2
fi
LOADER="$1"
PHASE="$2"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

LOADER_DIR="loaders/${LOADER}"
if [ ! -d "$LOADER_DIR" ]; then
  echo "No such loader directory: $LOADER_DIR" >&2
  exit 2
fi

if [ "$PHASE" = run ] && [ -z "${DISPLAY:-}" ]; then
  echo "DISPLAY is not set. Start an X server first, e.g. Xvfb :99 & export DISPLAY=:99" >&2
  exit 2
fi

mkdir -p "${LOADER_DIR}/run"
LOG_FILE="${REPO_ROOT}/${LOADER_DIR}/run/ci-run-loader.log"

# Exporting as an environment variable ensures it safely passes to the forked Minecraft JVM
export GRUG_CI=true
gradle_args=(-Dgrug.activeLoader="$LOADER" --stacktrace)

# Standalone loaders have their own Gradle wrapper and consume core via mavenLocal.
if [ -f "${LOADER_DIR}/root.gradle" ]; then
  echo "==> Publishing core to mavenLocal"
  ./gradlew :core:publishMavenJavaPublicationToMavenLocal "${gradle_args[@]}" || exit 1
  cd "$LOADER_DIR"
  task_prefix=""
else
  task_prefix=":loaders:${LOADER}:"
fi

if [ "$PHASE" = build ]; then
  echo "==> Building $LOADER (compile only, no game launch)"
  ./gradlew "${task_prefix}build" -x test "${gradle_args[@]}"
  exit
fi

EXPECTED_TESTS=$(find "${REPO_ROOT}/mods" -name "*-Test.grug" | wc -l | tr -d ' ')
SUCCESS_MSG="[GRUG CI] ALL ${EXPECTED_TESTS} TESTS PASSED"
BOOT_MSG="[GRUG CI] BOOT TO TITLE SCREEN SUCCESSFUL"

if [ "$PHASE" = run ]; then
  if [ ! -f "${REPO_ROOT}/${LOADER_DIR}/test-save.txt" ]; then
    echo "==> ERROR: ${LOADER_DIR}/test-save.txt is missing. Every loader must specify a test save." >&2
    exit 1
  fi

  # Read the target zip name, trimming any whitespace/newlines
  SAVE_ZIP=$(tr -d ' \n\r' < "${REPO_ROOT}/${LOADER_DIR}/test-save.txt")
  
  if [ -z "$SAVE_ZIP" ] || [ ! -f "${REPO_ROOT}/test-saves/${SAVE_ZIP}" ]; then
    echo "==> ERROR: ${LOADER_DIR}/test-save.txt specifies '${SAVE_ZIP}', but the zip was not found in test-saves/." >&2
    exit 1
  fi

  echo "==> Unzipping test-saves/${SAVE_ZIP} into ${LOADER_DIR}/run/saves/"
  mkdir -p "${REPO_ROOT}/${LOADER_DIR}/run/saves/"
  unzip -q -o "${REPO_ROOT}/test-saves/${SAVE_ZIP}" -d "${REPO_ROOT}/${LOADER_DIR}/run/saves/"
fi

timeout_secs="${GRUG_CI_RUN_TIMEOUT:-120}"
crash_re='Uncaught exception in thread "Minecraft main thread"|Exception in thread "Minecraft main thread"|Game crashed! Crash report saved to'

gradle_pid=""
tail_pid=""

cleanup() {
  [ -n "$tail_pid" ] && kill "$tail_pid" 2>/dev/null || true
  [ -n "$gradle_pid" ] || return 0
  kill -TERM -- "-${gradle_pid}" 2>/dev/null || true
  for _ in $(seq 1 10); do
    kill -0 "$gradle_pid" 2>/dev/null || break
    sleep 1
  done
  kill -KILL -- "-${gradle_pid}" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

echo "==> Launching $LOADER, waiting up to ${timeout_secs}s for the tests to pass..."
: > "$LOG_FILE"

# Follow the log in the background so we get real-time output in the CI console
tail -f "$LOG_FILE" &
tail_pid=$!

# setsid gives the launch its own process group so cleanup can kill Gradle and the game together.
setsid ./gradlew "${task_prefix}runClient" "${gradle_args[@]}" --no-daemon >"$LOG_FILE" 2>&1 &
gradle_pid=$!

status=timeout
reached_title=false
deadline=$((SECONDS + timeout_secs))
while [ "$SECONDS" -lt "$deadline" ]; do
  if [ "$reached_title" = false ] && grep -q -F "$BOOT_MSG" "$LOG_FILE"; then
    echo "==> $LOADER reached the title screen. Waiting for tests to complete..."
    reached_title=true
  fi

  if grep -q -F "$SUCCESS_MSG" "$LOG_FILE"; then status=success; break; fi
  if grep -q "FAIL " "$LOG_FILE"; then status=failed; break; fi
  if grep -q -E "$crash_re" "$LOG_FILE"; then status=crashed; break; fi
  if ! kill -0 "$gradle_pid" 2>/dev/null; then status=exited; break; fi
  sleep 1
done

# Kill tail manually so it doesn't leak into the next steps
[ -n "$tail_pid" ] && kill "$tail_pid" 2>/dev/null || true
tail_pid=""

case "$status" in
  success)
    echo "==> $LOADER successfully ran and passed all tests."
    ;;
  failed)
    echo "==> $LOADER tests failed." >&2
    grep -n -B2 -A10 "FAIL " "$LOG_FILE" >&2 || true
    ;;
  crashed)
    if [ "$reached_title" = true ]; then
      echo "==> $LOADER's game thread crashed AFTER reaching the title screen." >&2
    else
      echo "==> $LOADER's game thread crashed BEFORE reaching the title screen." >&2
    fi
    echo "==> First fatal error in $LOG_FILE:" >&2
    grep -n -m1 -B2 -A14 -E "$crash_re" "$LOG_FILE" >&2 || true
    ;;
  exited)
    wait "$gradle_pid"
    if [ "$reached_title" = true ]; then
      echo "==> $LOADER's build/game process exited (exit code $?) after reaching the title screen but before passing tests." >&2
    else
      echo "==> $LOADER's build/game process exited (exit code $?) before reaching the title screen." >&2
    fi
    echo "==> See $LOG_FILE" >&2
    ;;
  timeout)
    if [ "$reached_title" = true ]; then
      echo "==> Timed out after ${timeout_secs}s waiting for $LOADER tests to pass (but it DID reach the title screen)." >&2
    else
      echo "==> Timed out after ${timeout_secs}s waiting for $LOADER to reach the title screen." >&2
    fi
    echo "==> Check $LOG_FILE, and raise GRUG_CI_RUN_TIMEOUT if it was still making progress." >&2

    if command -v jcmd >/dev/null 2>&1; then
      for jp in $(jcmd -l 2>/dev/null | awk '!/[Gg]radle|JCmd/ {print $1}'); do
        echo "==> Thread dump of JVM $jp (game main thread and AWT event thread):" >&2
        jcmd "$jp" Thread.print 2>&1 \
          | grep -A20 -E '^"(Minecraft main thread|AWT-EventQueue-0)"' >&2 || true
      done
    fi
    ;;
esac

[ "$status" = success ]
