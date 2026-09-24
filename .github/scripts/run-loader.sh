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
  echo "DISPLAY is not set. Start an X server first, e.g. run under xvfb-run." >&2
  exit 2
fi

mkdir -p "${LOADER_DIR}/run"
LOG_FILE="${REPO_ROOT}/${LOADER_DIR}/run/ci-run-loader.log"
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

timeout_secs="${GRUG_CI_RUN_TIMEOUT:-120}"
boot_msg='[GRUG CI] BOOT TO TITLE SCREEN SUCCESSFUL'
crash_re='Uncaught exception in thread "Minecraft main thread"|Exception in thread "Minecraft main thread"|Game crashed! Crash report saved to'

gradle_pid=""

cleanup() {
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

echo "==> Launching $LOADER, waiting up to ${timeout_secs}s for the game to reach the title screen..."
: > "$LOG_FILE"

# setsid gives the launch its own process group so cleanup can kill Gradle and the game together.
setsid ./gradlew "${task_prefix}runClient" "${gradle_args[@]}" --no-daemon >"$LOG_FILE" 2>&1 &
gradle_pid=$!

status=timeout
deadline=$((SECONDS + timeout_secs))
while [ "$SECONDS" -lt "$deadline" ]; do
  if grep -q -F "$boot_msg" "$LOG_FILE"; then status=booted; break; fi
  if grep -q -E "$crash_re" "$LOG_FILE"; then status=crashed; break; fi
  if ! kill -0 "$gradle_pid" 2>/dev/null; then status=exited; break; fi
  sleep 1
done

case "$status" in
  booted)
    echo "==> $LOADER successfully reached its title screen."
    ;;
  crashed)
    echo "==> $LOADER's game thread crashed before reaching the title screen (not waiting for the timeout)." >&2
    echo "==> First fatal error in $LOG_FILE:" >&2
    grep -n -m1 -B2 -A14 -E "$crash_re" "$LOG_FILE" >&2 || true
    ;;
  exited)
    wait "$gradle_pid"
    echo "==> $LOADER's build/game process exited before reaching the title screen (exit code $?)." >&2
    echo "==> See $LOG_FILE" >&2
    ;;
  timeout)
    echo "==> Timed out after ${timeout_secs}s waiting for $LOADER's title screen." >&2
    echo "==> This can be a genuine hang or (especially on a first run) still-in-progress downloads." >&2
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

[ "$status" = booted ]
