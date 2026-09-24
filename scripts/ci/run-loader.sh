#!/usr/bin/env bash
# scripts/ci/run-loader.sh <loader-dir-name> <resolve|build|run>

set -uo pipefail

usage() {
  echo "Usage: $0 <loader-dir-name> <resolve|build|run>" >&2
  exit 2
}

[ "$#" -eq 2 ] || usage
LOADER="$1"
PHASE="$2"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

LOADER_DIR="loaders/${LOADER}"
if [ ! -d "$LOADER_DIR" ]; then
  echo "No such loader directory: $LOADER_DIR" >&2
  exit 2
fi

RUN_DIR="${LOADER_DIR}/run"
mkdir -p "$RUN_DIR"
LOG_FILE="${RUN_DIR}/ci-run-loader.log"
PID_FILE="${RUN_DIR}/ci-run-loader.pid"

is_standalone() {
  [ -f "${LOADER_DIR}/root.gradle" ]
}

publish_core_local() {
  echo "==> Publishing core to mavenLocal"
  ./gradlew ":core:publishMavenJavaPublicationToMavenLocal" -Dgrug.activeLoader="$LOADER" --stacktrace
}

sweep_stale_processes() {
  if [ -f "$PID_FILE" ]; then
    local old_pid
    old_pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
      echo "==> Found a still-running process from a previous run (pid $old_pid), stopping it" >&2
      kill -TERM -- "-${old_pid}" 2>/dev/null || true
      for _ in $(seq 1 10); do
        kill -0 "$old_pid" 2>/dev/null || break
        sleep 1
      done
      kill -KILL -- "-${old_pid}" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
  fi
  pkill -KILL -f "${LOADER_DIR}/" 2>/dev/null || true
}

case "$PHASE" in
  resolve)
    echo "==> Resolving dependencies for $LOADER"
    if is_standalone; then
      publish_core_local
      (cd "$LOADER_DIR" && ./gradlew dependencies -Dgrug.activeLoader="$LOADER" --stacktrace)
    else
      ./gradlew ":loaders:${LOADER}:dependencies" -Dgrug.activeLoader="$LOADER" --stacktrace
    fi
    ;;

  build)
    echo "==> Building $LOADER (compile only, no game launch)"
    if is_standalone; then
      publish_core_local
      (cd "$LOADER_DIR" && ./gradlew build -x test -Dgrug.activeLoader="$LOADER" --stacktrace)
    else
      ./gradlew ":loaders:${LOADER}:build" -x test -Dgrug.activeLoader="$LOADER" --stacktrace
    fi
    ;;

  run)
    if [ -z "${DISPLAY:-}" ]; then
      echo "DISPLAY is not set. Start a real X server or Xdummy first, e.g.:" >&2
      echo "  sudo Xorg -noreset -ac -config ./dummy.xorg.conf :99 & export DISPLAY=:99" >&2
      exit 2
    fi

    if [ "$LOADER" != "1.20.6-forge" ]; then
      if ! command -v xrandr >/dev/null 2>&1; then
        echo "xrandr is required for LWJGL 2 loaders (apt-get install x11-xserver-utils)." >&2
        exit 2
      fi
      if ! xrandr -q >/dev/null 2>&1; then
        echo "The X server on DISPLAY=$DISPLAY has no usable RANDR extension." >&2
        echo "For Xvfb, start it with: -screen 0 1280x720x24 +extension RANDR" >&2
        exit 2
      fi
      export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dorg.lwjgl.util.Debug=true"
    fi

    timeout_secs="${GRUG_CI_RUN_TIMEOUT:-120}"

    sweep_stale_processes

    gradle_pid=""
    watch_pid=""
    crash_pid=""

    cleanup() {
      local reason="$1"
      [ -n "$reason" ] && echo "==> $reason, stopping $LOADER" >&2
      if [ -n "$watch_pid" ]; then
        kill -KILL -- "$watch_pid" 2>/dev/null || true
      fi
      if [ -n "$crash_pid" ]; then
        { kill -KILL -- "$crash_pid"; wait "$crash_pid"; } 2>/dev/null || true
      fi
      if [ -n "$gradle_pid" ]; then
        kill -TERM -- "-${gradle_pid}" 2>/dev/null || true
        for _ in $(seq 1 10); do
          kill -0 "$gradle_pid" 2>/dev/null || break
          sleep 1
        done
        kill -KILL -- "-${gradle_pid}" 2>/dev/null || true
      fi

      echo "==> Stopping Gradle daemons" >&2
      ./gradlew --stop >/dev/null 2>&1 || true
      if is_standalone; then
        (cd "$LOADER_DIR" && ./gradlew --stop >/dev/null 2>&1 || true)
      fi

      sweep_stale_processes
    }
    trap 'cleanup "Interrupted"; exit 130' INT
    trap 'cleanup "Terminated"; exit 143' TERM

    echo "==> Launching $LOADER, waiting up to ${timeout_secs}s for the game to reach the title screen..."
    : > "$LOG_FILE"

    if is_standalone; then
      publish_core_local
      setsid bash -c "cd \"$LOADER_DIR\" && ./gradlew runClient -Dgrug.activeLoader=\"$LOADER\" --no-daemon --stacktrace" \
        >"$LOG_FILE" 2>&1 &
    else
      setsid bash -c "./gradlew \":loaders:${LOADER}:runClient\" -Dgrug.activeLoader=\"$LOADER\" --no-daemon --stacktrace" \
        >"$LOG_FILE" 2>&1 &
    fi
    gradle_pid=$!
    echo "$gradle_pid" > "$PID_FILE"

    # Block on whichever happens first: the success string appears, or we time out.
    ( exec timeout "${timeout_secs}" bash -c "while ! grep -q -F '[GRUG CI] BOOT TO TITLE SCREEN SUCCESSFUL' \"$LOG_FILE\" 2>/dev/null; do sleep 1; done" ) &
    watch_pid=$!

    crash_re='Uncaught exception in thread "Minecraft main thread"|Exception in thread "Minecraft main thread"|Game crashed! Crash report saved to'
    ( while ! grep -q -E "$crash_re" "$LOG_FILE" 2>/dev/null; do sleep 1; done ) &
    crash_pid=$!

    wait -n
    status="timeout"
    exit_code=""
    if ! kill -0 "$gradle_pid" 2>/dev/null; then
      status="exited"
    elif ! kill -0 "$watch_pid" 2>/dev/null; then
      wait "$watch_pid"
      if [ "$?" -eq 0 ]; then
        status="booted"
      else
        status="timeout"
      fi
    elif ! kill -0 "$crash_pid" 2>/dev/null; then
      status="crashed"
    else
      wait "$gradle_pid"
      exit_code=$?
      status="exited"
    fi

    case "$status" in
      crashed)
        echo "==> $LOADER's game thread crashed before reaching the title screen (not waiting for the timeout)." >&2
        echo "==> LWJGL debug lines from $LOG_FILE:" >&2
        grep -h '\[LWJGL\]' "$LOG_FILE" | head -20 >&2 || true
        echo "==> First fatal error in $LOG_FILE:" >&2
        grep -n -m1 -B2 -A14 -E "$crash_re" "$LOG_FILE" >&2 || true
        ;;
      booted)
        echo "==> $LOADER successfully reached its title screen."
        ;;
      exited)
        if [ -z "$exit_code" ]; then
          wait "$gradle_pid"
          exit_code=$?
        fi
        echo "==> $LOADER's build/game process exited before reaching the title screen (exit code ${exit_code})." >&2
        echo "==> See $LOG_FILE" >&2
        ;;
      timeout)
        echo "==> Timed out after ${timeout_secs}s waiting for $LOADER's title screen." >&2
        echo "==> The process was still running when the timeout hit; this can mean a genuine hang," >&2
        echo "==> or (especially on a first run) still-in-progress downloads. Check $LOG_FILE" >&2
        echo "==> for the last thing Gradle/the game printed before raising GRUG_CI_RUN_TIMEOUT." >&2

        if command -v jcmd >/dev/null 2>&1; then
          for jp in $(jcmd -l 2>/dev/null | awk '!/[Gg]radle|JCmd/ {print $1}'); do
            echo "==> Thread dump of JVM $jp (game main thread and AWT event thread):" >&2
            jcmd "$jp" Thread.print 2>&1 \
              | grep -A20 -E '^"(Minecraft main thread|AWT-EventQueue-0)"' >&2 || true
          done
        fi
        ;;
    esac

    cleanup ""
    trap - INT TERM
    rm -f "$PID_FILE"

    [ "$status" = "booted" ] || exit 1
    ;;

  *)
    usage
    ;;
esac
