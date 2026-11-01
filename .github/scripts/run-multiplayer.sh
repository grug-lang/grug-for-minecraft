#!/usr/bin/env bash
# .github/scripts/run-multiplayer.sh <loader-dir-name>
#
# Runs a loader's suite on a dedicated server with a real client connected to it (#167): the loader's
# runServer in the background, then, once the server says it is ready, its runClient pointed at the
# server through the game's quick play. The server drives the tests (GrugServerRun) and stops itself
# when they end; this script reads its log for the same [GRUG CI] lines run-loader.sh watches, and
# reads the client's log only for a crash, so a failure names which of the two processes died.
#
# Only the tests named by GRUG_CI_TESTS run, a comma separated list of runner paths, because a
# dedicated server cannot run a test that needs a screen (#168 replaces this with a declaration in
# the test). The client is not started with GRUG_CI, so it opens no world of its own and runs no
# tests: it is only the player the server's tests are built around.
#
# Needs a display for the client, so run it under xvfb-run. Only 1.20.6-forge has a server run so far.

set -uo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <loader-dir-name>" >&2
  exit 2
fi
LOADER="$1"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

LOADER_DIR="loaders/${LOADER}"
if [ ! -d "$LOADER_DIR" ]; then
  echo "No such loader directory: $LOADER_DIR" >&2
  exit 2
fi
if [ -z "${DISPLAY:-}" ]; then
  echo "DISPLAY is not set. Start an X server first, e.g. run under xvfb-run." >&2
  exit 2
fi
if [ -z "${GRUG_CI_TESTS:-}" ]; then
  echo "GRUG_CI_TESTS is not set, but a dedicated server needs the list of tests it can run." >&2
  exit 2
fi
EXPECTED_TESTS=$(python3 -c 'import sys; print(len({t.strip() for t in sys.argv[1].split(",")}))' "$GRUG_CI_TESTS")

SERVER_DIR="${REPO_ROOT}/${LOADER_DIR}/run-server"
CLIENT_DIR="${REPO_ROOT}/${LOADER_DIR}/run"
SERVER_LOG="${REPO_ROOT}/${LOADER_DIR}/run-server-ci.log"
CLIENT_LOG="${REPO_ROOT}/${LOADER_DIR}/run-client-ci.log"
PORT=25565
SUCCESS_MSG="[GRUG CI] ALL ${EXPECTED_TESTS} TESTS PASSED"

# The same crash lines run-loader.sh watches, and a Rust abort, which kills the process.
crash_re='Uncaught exception in thread "Server thread"|Exception in thread "main"|Uncaught exception in thread "Minecraft main thread"|Exception in thread "Minecraft main thread"|Game crashed! Crash report saved to|Broken grug invariant|Failed to start the minecraft server|panicked at .*\.rs:[0-9]+:[0-9]+'

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.util.Arrays.useLegacyMergeSort=true"
export RUST_BACKTRACE="${RUST_BACKTRACE:-full}"

gradle_args=(-Dgrug.activeLoader="$LOADER" --stacktrace --no-daemon)
if [ -n "${GRUG_PREBUILT_GRUG_RS:-}" ]; then
  gradle_args+=(-Pgrug.prebuiltGrugRs="$GRUG_PREBUILT_GRUG_RS")
fi

# The server's world is a pristine copy of the loader's test save every launch, as run-loader.sh does
# for the client. The save's folder name is the level name the server has to be told.
SAVE_ZIP=$(tr -d ' \n\r' < "${REPO_ROOT}/${LOADER_DIR}/test-save.txt")
rm -rf "$SERVER_DIR"
mkdir -p "$SERVER_DIR"
python3 -m zipfile -e "${REPO_ROOT}/test-saves/${SAVE_ZIP}" "$SERVER_DIR/"
LEVEL_NAME=$(python3 - "$SERVER_DIR" <<'PY'
import os, sys
names = [n for n in sorted(os.listdir(sys.argv[1])) if os.path.isdir(os.path.join(sys.argv[1], n))]
print(names[0])
PY
)
echo "eula=true" > "${SERVER_DIR}/eula.txt"
# online-mode is off because a development client has no Mojang session to prove, and view-distance
# is small because the tests build in a room around the player.
cat > "${SERVER_DIR}/server.properties" <<PROPS
online-mode=false
level-name=${LEVEL_NAME}
server-port=${PORT}
view-distance=4
simulation-distance=4
max-tick-time=-1
spawn-protection=0
PROPS

# Forge's cosmetic early window can only hurt a headless run (see run-loader.sh).
for dir in "$SERVER_DIR" "$CLIENT_DIR"; do
  mkdir -p "${dir}/config"
  printf '#Disable the cosmetic early load window (CI has no desktop to fall back to).\nearlyWindowControl = false\n' > "${dir}/config/fml.toml"
done
rm -rf "${CLIENT_DIR}/saves"

server_pid=""
client_pid=""
tail_pid=""
cleanup() {
  local status=$?
  # A run that did not pass leaves the stacks of both game JVMs next to the logs, which is the only
  # way to see where a process that went quiet was stuck.
  if [ "$status" -ne 0 ]; then
    for pid in $(pgrep -f 'forge_userdev|ForgeBootstrap|BootstrapLauncher' 2>/dev/null); do
      echo "==> Thread dump of java process ${pid}" >> "${REPO_ROOT}/${LOADER_DIR}/run-threads-ci.log"
      jcmd "$pid" Thread.print >> "${REPO_ROOT}/${LOADER_DIR}/run-threads-ci.log" 2>&1 || true
    done
  fi
  [ -n "$tail_pid" ] && kill "$tail_pid" 2>/dev/null || true
  for pid in "$client_pid" "$server_pid"; do
    [ -n "$pid" ] && kill -TERM -- "-${pid}" 2>/dev/null || true
  done
  sleep 3
  for pid in "$client_pid" "$server_pid"; do
    [ -n "$pid" ] && kill -KILL -- "-${pid}" 2>/dev/null || true
  done
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

: > "$SERVER_LOG"
: > "$CLIENT_LOG"
tail -n +1 -F "$SERVER_LOG" 2>/dev/null | sed -u 's/^/[server] /' &
tail_pid=$!

# setsid gives each process its own group, so the cleanup ends Gradle and the game together.
echo "==> Building and starting the dedicated server of ${LOADER}"
GRUG_CI=true setsid ./gradlew ":loaders:${LOADER}:classes" ":loaders:${LOADER}:runServer" "${gradle_args[@]}" -x test >"$SERVER_LOG" 2>&1 &
server_pid=$!

build_deadline=$((SECONDS + ${GRUG_CI_BUILD_TIMEOUT:-600}))
while ! grep -q 'Done (.*)! For help, type "help"' "$SERVER_LOG"; do
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "==> The dedicated server process exited before it was ready." >&2
    exit 1
  fi
  if [ "$SECONDS" -ge "$build_deadline" ]; then
    echo "==> The dedicated server was not ready after ${GRUG_CI_BUILD_TIMEOUT:-600}s." >&2
    exit 1
  fi
  sleep 2
done
echo "==> The dedicated server is ready. Starting a client that joins 127.0.0.1:${PORT}"

env -u GRUG_CI -u GRUG_CI_TESTS GRUG_CI_CONNECT="127.0.0.1:${PORT}" setsid ./gradlew ":loaders:${LOADER}:runClient" "${gradle_args[@]}" -x test >"$CLIENT_LOG" 2>&1 &
client_pid=$!
tail -n +1 -F "$CLIENT_LOG" 2>/dev/null | sed -u 's/^/[client] /' &

deadline=$((SECONDS + ${GRUG_CI_RUN_TIMEOUT:-300}))
while :; do
  if grep -qF "$SUCCESS_MSG" "$SERVER_LOG"; then
    echo "==> ${SUCCESS_MSG}, run on the dedicated server with a client connected."
    exit 0
  fi
  if grep -qF '[GRUG CI] FAIL' "$SERVER_LOG"; then
    echo "==> A test failed on the dedicated server." >&2
    grep -m1 -B 5 -A 10 -F '[GRUG CI] FAIL' "$SERVER_LOG" >&2
    exit 1
  fi
  if line=$(grep -m1 -E "$crash_re" "$SERVER_LOG"); then
    echo "==> The dedicated server crashed: ${line}" >&2
    exit 1
  fi
  if line=$(grep -m1 -E "$crash_re" "$CLIENT_LOG"); then
    echo "==> The client crashed: ${line}" >&2
    exit 1
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "==> The dedicated server exited without ${SUCCESS_MSG}." >&2
    exit 1
  fi
  if ! kill -0 "$client_pid" 2>/dev/null && ! grep -q '\[GRUG CI\] CI mode active' "$SERVER_LOG"; then
    echo "==> The client exited before the server started the tests." >&2
    exit 1
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "==> Timed out after ${GRUG_CI_RUN_TIMEOUT:-300}s without ${SUCCESS_MSG}." >&2
    exit 1
  fi
  sleep 2
done
