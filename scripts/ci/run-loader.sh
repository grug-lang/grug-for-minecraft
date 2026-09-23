#!/usr/bin/env bash
# scripts/ci/run-loader.sh <loader-dir-name> <resolve|build|run>
#
# Single entry point for resolving, building, and booting one
# grug-for-minecraft loader. Used by .github/workflows/build.yml, and safe
# to run by hand the same way, from the repo root:
#
#   scripts/ci/run-loader.sh 1.20.6-forge resolve
#   scripts/ci/run-loader.sh 1.20.6-forge build
#   DISPLAY=:99 scripts/ci/run-loader.sh 1.20.6-forge run
#
# <loader-dir-name> is the directory name under loaders/, e.g. "1.20.6-forge"
# or "b1.7.3-ornithe".
#
# Phases:
#   resolve  Downloads dependencies only. Fails fast on Maven/repo problems
#            (Mojang, Fabric, Ornithe, Forge, glass-launcher, JitPack,
#            Modrinth) without compiling anything.
#   build    Compiles the loader (and, transitively, core). Does not launch
#            the game.
#   run      Launches the game under the current DISPLAY and waits for it to
#            reach its title screen (see "Startup signal" below). This is
#            the phase that actually needs Xvfb.
#
# Ornithe loaders (b1.7.3-ornithe, a1.1.2_01-ornithe) are standalone Gradle 9
# builds with their own gradlew, settings.gradle and root.gradle; the rest
# (Forge, StationAPI) are normal subprojects of the repo's root Gradle
# build. This script detects which is which by the presence of
# loaders/<dir>/root.gradle and drives each accordingly, so both `resolve`
# and `build` publish core to mavenLocal first for the standalone loaders,
# same as their own root.gradle runClient task already does.
#
# Startup signal:
#   Rather than adding a Mixin per loader just to hook the title screen,
#   this waits for the game's own window to report the title each loader
#   already sets once its client has finished initializing (see the
#   "grug$onInit" / "grug$onClientTick" Mixins and GrugModLoader.java in
#   each loader). That title is only ever set after grug has compiled every
#   mod without error (a compile failure throws during mod init, which
#   happens earlier, and crashes the client before any window appears), so
#   seeing it is both necessary and sufficient for "started up" as defined
#   in the workflow. Detection uses `xdotool search --sync`, which blocks
#   on X server events instead of polling, since Display.setTitle() is a
#   native window property, not a log line.
#
#   IMPORTANT: a window title match only proves *some* window with that
#   title exists on the display right now, not that our own Gradle/game
#   process just created it. A stale window left over from an earlier,
#   improperly killed run would match instantly and produce a false
#   "booted after 0s". To make that impossible rather than just unlikely,
#   this script (a) sweeps away anything matching this loader's path
#   before starting a new run, and (b) traps INT/TERM so Ctrl-C (or a CI
#   job cancellation) actually kills the backgrounded process group instead
#   of leaving it running detached (see is_standalone's use of setsid,
#   which isolates the child from the terminal precisely so CI's nested
#   Ornithe daemons get cleaned up too -- but that same isolation means the
#   OS won't deliver Ctrl-C to it for free; we have to forward it).
#
# Env vars (all optional):
#   GRUG_CI_RUN_TIMEOUT   Seconds to wait for the title screen in the `run`
#                         phase before giving up. Default 60 (first runs
#                         download Minecraft, mappings, and mod
#                         dependencies from several Maven hosts).

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

# Kills anything left running from a previous `run` invocation of this
# script for this specific loader, so its window can never be mistaken for
# a fresh boot.
#
# This is PID-based, via $PID_FILE, not a cmdline string match: we can't
# assume the actual game JVM's command line mentions this loader's path
# (that depends on how each build tool invokes it, which differs across
# Forge/ForgeGradle, Loom and Babric loom, and isn't something we can
# verify without a real, successful run). What we *do* know is the PID we
# ourselves launched last time (setsid makes it a process group leader, so
# killing -PID takes its whole tree with it, nested Ornithe build
# included). Falls back to a path-based sweep too, as a secondary net for
# anything not started by this script (e.g. a manual test run).
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
    expected_title=""
    case "$LOADER" in
      1.20.6-forge)      expected_title="Minecraft 1.20.6 - Forge with grug" ;;
      b1.7.3-ornithe)    expected_title="Minecraft Beta 1.7.3 - Ornithe with grug" ;;
      b1.7.3-stationapi) expected_title="Minecraft Beta 1.7.3 - StationAPI with grug" ;;
      a1.1.2_01-ornithe) expected_title="Minecraft Alpha 1.1.2_01 - Ornithe with grug" ;;
      *)
        echo "No known startup window title for loader '$LOADER'." >&2
        echo "Add one to the case statement in $0 before adding it to CI." >&2
        exit 2
        ;;
    esac

    if [ -z "${DISPLAY:-}" ]; then
      echo "DISPLAY is not set. Start Xvfb first, e.g.:" >&2
      echo "  Xvfb :99 -screen 0 1280x720x24 +extension RANDR +extension GLX & export DISPLAY=:99" >&2
      exit 2
    fi
    if ! command -v xdotool >/dev/null 2>&1; then
      echo "xdotool is required for the 'run' phase (apt-get install xdotool)." >&2
      exit 2
    fi

    timeout_secs="${GRUG_CI_RUN_TIMEOUT:-60}"

    # Clear out anything left behind by a previous run of this loader
    # (including one killed by Ctrl-C before the trap below existed, or one
    # that never got this far) so its window can't be mistaken for a fresh
    # boot below.
    sweep_stale_processes

    gradle_pid=""
    watch_pid=""

    cleanup() {
      local reason="$1"
      [ -n "$reason" ] && echo "==> $reason, stopping $LOADER" >&2
      if [ -n "$watch_pid" ]; then
        kill -KILL -- "$watch_pid" 2>/dev/null || true
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

    echo "==> Launching $LOADER, waiting up to ${timeout_secs}s for window title: $expected_title"
    : > "$LOG_FILE"

    if is_standalone; then
      publish_core_local
      setsid bash -c "cd \"$LOADER_DIR\" && ./gradlew runClient -Dgrug.activeLoader=\"$LOADER\" --no-daemon --stacktrace" \
        >"$LOG_FILE" 2>&1 &
    else
      setsid ./gradlew ":loaders:${LOADER}:runClient" -Dgrug.activeLoader="$LOADER" --no-daemon --stacktrace \
        >"$LOG_FILE" 2>&1 &
    fi
    gradle_pid=$!
    echo "$gradle_pid" > "$PID_FILE"

    # Block on whichever happens first: the title appears, or we time out.
    # xdotool --sync waits on X server events instead of polling, so this
    # holds one connection open instead of reopening one every couple of
    # seconds (which is also what was spamming Xvfb's keymap-compiler
    # warnings on every reconnect).
    ( exec timeout "${timeout_secs}" xdotool search --sync --name "$expected_title" \
        >/dev/null 2>&1 ) &
    watch_pid=$!

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
    else
      # Neither has finished yet but wait -n returned (e.g. a signal we
      # don't trap); fall back to waiting on the game process itself. Only
      # `wait` on $gradle_pid once: a second `wait` on an already-reaped
      # pid fails and would misreport the exit code.
      wait "$gradle_pid"
      exit_code=$?
      status="exited"
    fi

    case "$status" in
      booted)
        echo "==> $LOADER reached its title screen."
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
