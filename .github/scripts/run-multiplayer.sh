#!/usr/bin/env bash
# .github/scripts/run-multiplayer.sh <loader-dir-name>
#
# Runs a loader's suite on a dedicated server with a real client connected to it (#167): the loader's
# runServer in the background, then, once the server says it is ready, its runClient pointed at the
# server through the game's quick play. The server drives the tests (GrugServerRun) and stops itself
# when they end; this script reads its log for the same [GRUG CI] lines run-loader.sh watches, and
# reads the client's log only for a crash, so a failure names which of the two processes died.
#
# The server decides what it runs: a test runs there only when it defines
# tick_any_side instead of tick (#168), and the runner skips the others and prints how many it
# skipped. This script reads no test file. It waits for `[GRUG CI] ALL <n> TESTS PASSED` with any
# n of at least 1 and prints the line, so a run that selected nothing is not green. GRUG_CI_TESTS, a
# comma separated list of runner paths, narrows the run further for a try-out. The client is not
# started with GRUG_CI, so it opens no world of its own and runs no tests: it is only the player the
# server's tests are built around.
#
# A loader whose directory holds server-version.txt has no runServer: its dedicated server is a
# game of its own that Ornithe's native installer sets up (a1.1.2_01-ornithe, whose Alpha server
# a0.2.1 is numbered apart from the 1.1.2_01 client, #1021, and b1.7.3-ornithe, #163). The script then builds core's release
# jar and the loader's layer, assembles them into the universal jar a player would drop in, installs
# the server beside it with OSL, and starts it from the jar; the client still comes from runClient.
#
# A standalone loader without server-version.txt (b1.7.3-stationapi, #165) runs the dedicated server
# the way a development client runs: its own Gradle build's runServer, in run-server, after core has
# been published into build/maven for it to resolve.
#
# Needs a display for the client, so run it under xvfb-run. 1.2.5-forge, 1.20.6-forge, a1.1.2_01-ornithe,
# b1.7.3-ornithe and b1.7.3-stationapi have a server run so far.

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
SERVER_DIR="${REPO_ROOT}/${LOADER_DIR}/run-server"
CLIENT_DIR="${REPO_ROOT}/${LOADER_DIR}/run"
SERVER_LOG="${REPO_ROOT}/${LOADER_DIR}/run-server-ci.log"
CLIENT_LOG="${REPO_ROOT}/${LOADER_DIR}/run-client-ci.log"
PORT=25565
SUCCESS_RE='\[GRUG CI\] ALL [1-9][0-9]* TESTS PASSED'
# Forge prints `Done (1.2s)! For help`, Alpha prints `Done! For help`.
READY_RE='Done( \(.*\))?! For help, type "help"'
ORNITHE_INSTALLER_VERSION=0.5.4
SERVER_VERSION=""
if [ -f "${LOADER_DIR}/server-version.txt" ]; then
  SERVER_VERSION=$(tr -d ' \n\r' < "${LOADER_DIR}/server-version.txt")
fi
STANDALONE=false
[ -f "${LOADER_DIR}/root.gradle" ] && STANDALONE=true

# The same crash lines run-loader.sh watches, and a Rust abort, which kills the process.
crash_re='Uncaught exception in thread "Server thread"|Exception in thread "main"|Uncaught exception in thread "Minecraft main thread"|Exception in thread "Minecraft main thread"|Game crashed! Crash report saved to|Broken grug invariant|Failed to start the minecraft server|panicked at .*\.rs:[0-9]+:[0-9]+'

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.util.Arrays.useLegacyMergeSort=true"
export RUST_BACKTRACE="${RUST_BACKTRACE:-full}"

# The test-control channel's fault injection (#1040): the server waits 5 s for an answer (30 s on Alpha,
# whose software-rendered client took over 3 s to read the request while it loaded chunks, and stays
# under Alpha's 60 s silence timeout), and the
# joined client never answers a capture 7 pixels wide, and holds the answer to one 9 pixels wide until it handles the next request, so the stale answer lands while that request waits.
# mods/coverage's forwarded_ tests expect exactly those errors, so a run that passes proves the
# timeout, and that a late answer is dropped.
CONTROL_TIMEOUT_MILLIS=5000
if [ "$LOADER" = "a1.1.2_01-ornithe" ]; then
  CONTROL_TIMEOUT_MILLIS=30000
fi
# The Beta and 1.2.5 clients read their socket with a 30 s timeout, and a frozen server sends nothing, so
# their wait stays under it.
if [ "$LOADER" = "1.2.5-forge" ] || [ "$LOADER" = "b1.7.3-ornithe" ] || [ "$LOADER" = "b1.7.3-stationapi" ]; then
  CONTROL_TIMEOUT_MILLIS=20000
fi
export GRUG_CI_CONTROL_TIMEOUT_MILLIS=$CONTROL_TIMEOUT_MILLIS

gradle_args=(-Dgrug.activeLoader="$LOADER" --stacktrace --no-daemon)
if [ -n "${GRUG_PREBUILT_GRUG_RS:-}" ]; then
  gradle_args+=(-Pgrug.prebuiltGrugRs="$GRUG_PREBUILT_GRUG_RS")
fi

# The server's world is a pristine copy of the loader's test save every launch, as run-loader.sh does
# for the client. The save's folder name is the level name the server has to be told.
SAVE_ZIP=$(tr -d ' \n\r' < "${REPO_ROOT}/${LOADER_DIR}/test-save.txt")
rm -rf "$SERVER_DIR"
mkdir -p "$SERVER_DIR"
if ! python3 -m zipfile -e "${REPO_ROOT}/test-saves/${SAVE_ZIP}" "$SERVER_DIR/"; then
  echo "The test save test-saves/${SAVE_ZIP} is missing or is not a valid zip." >&2
  exit 1
fi
LEVEL_NAME=$(python3 - "$SERVER_DIR" <<'PY'
import os, sys
names = [n for n in sorted(os.listdir(sys.argv[1])) if os.path.isdir(os.path.join(sys.argv[1], n))]
print(names[0] if names else "")
PY
)
if [ -z "$LEVEL_NAME" ]; then
  echo "The test save test-saves/${SAVE_ZIP} holds no world folder, so the server has no level name." >&2
  exit 1
fi
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
if [ -n "$SERVER_VERSION" ] || [ "$STANDALONE" = true ]; then
  # An old server spawns monsters and animals around the player by itself, and a mob in the test
  # room or a hit on the test player would fail a test for a reason the test is not about.
  printf 'spawn-monsters=false\nspawn-animals=false\n' >> "${SERVER_DIR}/server.properties"
  # A test clears and rebuilds the floor under the player, and an old server kicks a player it
  # sees floating for too long, which ends the run with "the test player left".
  printf 'allow-flight=true\n' >> "${SERVER_DIR}/server.properties"
fi

# Forge's cosmetic early window can only hurt a headless run (see run-loader.sh).
if [ -z "$SERVER_VERSION" ] && [ "$STANDALONE" = false ]; then
  for dir in "$SERVER_DIR" "$CLIENT_DIR"; do
    mkdir -p "${dir}/config"
    printf '#Disable the cosmetic early load window (CI has no desktop to fall back to).\nearlyWindowControl = false\n' > "${dir}/config/fml.toml"
  done
fi
rm -rf "${CLIENT_DIR}/saves"
# A first launch shows the accessibility onboarding screen, which holds the client there and so
# never lets quick play connect; the options file says it was already shown.
mkdir -p "$CLIENT_DIR"
printf 'onboardAccessibility:false\nnarrator:0\n' > "${CLIENT_DIR}/options.txt"

server_pid=""
client_pid=""
tail_pid=""
client_tail_pid=""
cleanup() {
  local status=$?
  # A run that did not pass leaves the stacks of both game JVMs next to the logs, which is the only
  # way to see where a process that went quiet was stuck.
  if [ "$status" -ne 0 ]; then
    for pid in $(timeout 10 pgrep -f "${REPO_ROOT}.*(forge_userdev|ForgeBootstrap|BootstrapLauncher|fabric-server-launch|KnotServer)" 2>/dev/null); do
      echo "==> Thread dump of java process ${pid}" >> "${REPO_ROOT}/${LOADER_DIR}/run-threads-ci.log"
      timeout 20 jcmd "$pid" Thread.print >> "${REPO_ROOT}/${LOADER_DIR}/run-threads-ci.log" 2>&1 || true
    done
  fi
  for pid in "$tail_pid" "$client_tail_pid"; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
  done
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

# Builds what an installed Ornithe server loads, the way a player gets it: core's release jar and the
# loader's layer in the universal jar, beside the OSL bundle, in the mods folder of a server that
# Ornithe's native installer set up (#1021). The mods the suite tests are put where a player puts a
# mod they downloaded, and the bundled ones are turned on by their state, as launch-production.sh
# does. Everything it prints goes to the server's log, which the watch below reads.
install_ornithe_server() {
  local installer="${SERVER_DIR}/../ornithe-installer-cli"
  echo "==> Building the layer and core's release jar for ${LOADER}"
  GRUG_CI=true ./gradlew :core:shadowJar :core:publishMavenJavaPublicationToGrugBuildRepository \
    "${gradle_args[@]}" -x test || return 1
  # A standalone loader has its own Gradle wrapper, so its tasks have no project prefix.
  (cd "$LOADER_DIR" && GRUG_CI=true ./gradlew layerJar "${gradle_args[@]}" -x test) || return 1
  local core version
  # A restored build/ keeps the jars of every earlier commit, so the newest one is this build's.
  core=$(ls -t core/build/libs/*-release.jar | head -n1)
  version=$(basename "$core" | sed -E 's/^core-(.+)-release\.jar$/\1/')
  curl --fail --silent --show-error --location \
    "https://maven.ornithemc.net/releases/net/ornithemc/ornithe-installer-rs/ornithe-installer-rs-linux-x86_64/${ORNITHE_INSTALLER_VERSION}/ornithe-installer-rs-linux-x86_64-${ORNITHE_INSTALLER_VERSION}-cli" \
    -o "$installer" || return 1
  chmod +x "$installer"
  echo "==> Installing the Ornithe server ${SERVER_VERSION}"
  "$installer" server -m "$SERVER_VERSION" -d "$SERVER_DIR" || return 1
  mkdir -p "${SERVER_DIR}/mods" "${SERVER_DIR}/grug_mods"
  python3 .github/scripts/universal_jar.py assemble --core "$core" --version "$version" \
    --out "${SERVER_DIR}/mods/grug.jar" "${LOADER_DIR}/build/layer/grug-${LOADER}.jar" || return 1
  local osl
  osl=$(sed -n 's/^[[:space:]]*osl_version[[:space:]]*=[[:space:]]*//p' "${LOADER_DIR}/gradle.properties" | tr -d '\r' | head -n1)
  python3 - "$osl" "${SERVER_DIR}/mods/osl-${osl}.jar" <<'PY' || return 1
import json, sys, urllib.request
version, out = sys.argv[1], sys.argv[2]
request = urllib.request.Request(
    "https://api.modrinth.com/v2/project/osl/version",
    headers={"User-Agent": "grug-lang/grug-for-minecraft CI"},
)
versions = json.load(urllib.request.urlopen(request))
url = next(
    f["url"]
    for v in versions
    if v["version_number"] == version and "ornithe" in v["loaders"]
    for f in v["files"]
    if f["primary"]
)
urllib.request.urlretrieve(url, out)
PY
  cp -r mods/coverage "${SERVER_DIR}/grug_mods/"
  python3 - mods "${SERVER_DIR}/grug_mods_state.json" <<'PY'
import json, pathlib, sys
mods = sorted(p.name for p in pathlib.Path(sys.argv[1]).iterdir() if p.is_dir() and p.name != "coverage")
pathlib.Path(sys.argv[2]).write_text(json.dumps({"mods": {name: {"on": True} for name in mods}}) + "\n")
PY
}

# setsid gives each process its own group, so the cleanup ends Gradle and the game together.
echo "==> Building and starting the dedicated server of ${LOADER}"
if [ -n "$SERVER_VERSION" ]; then
  if ! install_ornithe_server >"$SERVER_LOG" 2>&1; then
    cat "$SERVER_LOG" >&2
    echo "==> Installing the dedicated server of ${LOADER} failed." >&2
    exit 1
  fi
  cd "$SERVER_DIR"
  GRUG_CI=true setsid java -jar fabric-server-launch.jar nogui >>"$SERVER_LOG" 2>&1 &
  server_pid=$!
  cd "$REPO_ROOT"
elif [ "$STANDALONE" = true ]; then
  # A standalone loader consumes core from the repository the root build publishes it into, so that
  # publish happens first, as run-loader.sh does.
  echo "==> Publishing core into build/maven" >"$SERVER_LOG"
  if ! GRUG_CI=true ./gradlew :core:publishMavenJavaPublicationToGrugBuildRepository \
    "${gradle_args[@]}" -x test >>"$SERVER_LOG" 2>&1; then
    cat "$SERVER_LOG" >&2
    echo "==> Publishing core for ${LOADER} failed." >&2
    exit 1
  fi
  cd "$LOADER_DIR"
  GRUG_CI=true setsid ./gradlew runServer "${gradle_args[@]}" -x test >>"$SERVER_LOG" 2>&1 &
  server_pid=$!
  cd "$REPO_ROOT"
else
  GRUG_CI=true setsid ./gradlew ":loaders:${LOADER}:classes" ":loaders:${LOADER}:runServer" "${gradle_args[@]}" -x test >"$SERVER_LOG" 2>&1 &
  server_pid=$!
fi

build_deadline=$((SECONDS + ${GRUG_CI_BUILD_TIMEOUT:-600}))
while ! grep -qE "$READY_RE" "$SERVER_LOG"; do
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

client_task=":loaders:${LOADER}:runClient"
client_dir="$REPO_ROOT"
if [ -f "${LOADER_DIR}/root.gradle" ]; then
  client_task=runClient
  client_dir="${REPO_ROOT}/${LOADER_DIR}"
fi
(cd "$client_dir" && exec env -u GRUG_CI -u GRUG_CI_TESTS -u GRUG_CI_CONTROL_TIMEOUT_MILLIS GRUG_CI_CONTROL_DROP_WIDTH=7 GRUG_CI_CONTROL_LATE_WIDTH=9 GRUG_CI_CONNECT="127.0.0.1:${PORT}" setsid ./gradlew "$client_task" "${gradle_args[@]}" -x test) >"$CLIENT_LOG" 2>&1 &
client_pid=$!
tail -n +1 -F "$CLIENT_LOG" 2>/dev/null | sed -u 's/^/[client] /' &
client_tail_pid=$!

deadline=$((SECONDS + ${GRUG_CI_RUN_TIMEOUT:-300}))
while :; do
  # A failure is checked before the success line, so a log holding both is a failure.
  if grep -qF '[GRUG CI] FAIL' "$SERVER_LOG"; then
    echo "==> A test failed on the dedicated server." >&2
    grep -m1 -B 5 -A 10 -F '[GRUG CI] FAIL' "$SERVER_LOG" >&2
    exit 1
  fi
  if line=$(grep -m1 -E "$SUCCESS_RE" "$SERVER_LOG"); then
    grep -E '\[GRUG CI\] Skipped ' "$SERVER_LOG"
    # A server skips a test that does not define tick_any_side and skips a forwarded_ test it
    # cannot forward, and either skip is a green run: the tests below have to show as passed. The
    # forwarded_ ones are the channel's own tests, including forwarded_world_settled, which is the
    # control for the forwarded settle answer (#1086). The pixel tests stay client-only until they
    # have a per-side reference (#1092); coverage_frame_saved_number runs on every server leg now
    # that a loader sends a saved number to the client's copy (#1093).
    if [ "$LOADER" = "1.20.6-forge" ] || [ "$LOADER" = "1.2.5-forge" ] || [ "$LOADER" = "a1.1.2_01-ornithe" ] || [ "$LOADER" = "b1.7.3-ornithe" ] || [ "$LOADER" = "b1.7.3-stationapi" ]; then
      for required in forwarded_capture forwarded_capture_dropped forwarded_capture_late forwarded_capture_late_then forwarded_world_settled coverage_frame_saved_number; do
        if ! grep -qF "[GRUG CI] PASS coverage/tests/${required}-Test.grug" "$SERVER_LOG"; then
          echo "==> The run passed, but coverage/tests/${required}-Test.grug did not run on the dedicated server." >&2
          exit 1
        fi
      done
    fi
    # A server leg that skips a use_block test (it is not tick_any_side, or the loader lists it as
    # unsupported) still passes its run, so every leg must show that these ran (#1090). The coverage
    # tests are the call itself and the face it clicks, and the other three are its first proof: a
    # block entity change, a placement, and the wrench.
    for use_block in coverage/tests/use_block_effects coverage/tests/use_block_faces buildcraft/tests/redstone_engine_wrench buildcraft/tests/wooden_pipe_wrench coverage/tests/decline_use_places; do
      if ! grep -qF "[GRUG CI] PASS ${use_block}-Test.grug" "$SERVER_LOG"; then
        echo "==> The run passed, but ${use_block}-Test.grug did not run on the dedicated server." >&2
        exit 1
      fi
    done
    # The tests #1032 promoted have to show as passed too, or a leg that skips one (its export
    # reverted, or the loader listing it as unsupported) would still look green. On the 1.20.6 leg
    # sound also exercises the new play_ui_sound forwarding, because that leg plays its button click;
    # a 1.2.5 leg plays nothing, so its PASS only proves the test ran there.
    for promoted in coverage/tests/decline_use coverage/tests/sound; do
      if ! grep -qF "[GRUG CI] PASS ${promoted}-Test.grug" "$SERVER_LOG"; then
        echo "==> The run passed, but ${promoted}-Test.grug did not run on the dedicated server." >&2
        exit 1
      fi
    done
    # The remote GUI test (#168) runs only on the 1.20.6 server so far: the other four servers
    # answer Test.screen_open for their nonexistent client, so their lists skip it until #1094
    # forwards the question, and only this leg has to show it ran. This catches the test not running
    # at all on that leg (renamed, listed as unsupported, or no longer recognised as declared),
    # which the run would otherwise pass with it silently skipped; a server that stops opening the
    # menu fails the test on its own tick budget, before this is reached.
    if [ "$LOADER" = "1.20.6-forge" ]; then
      remote_gui='[GRUG CI] PASS coverage/tests/remote_gui-Test.grug'
      if ! grep -qF "$remote_gui" "$SERVER_LOG"; then
        echo "==> The run passed, but coverage/tests/remote_gui-Test.grug did not run." >&2
        exit 1
      fi
    fi
    echo "==> ${line}, run on the dedicated server with a client connected."
    exit 0
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
    echo "==> The dedicated server exited without an ALL n TESTS PASSED line." >&2
    exit 1
  fi
  if ! kill -0 "$client_pid" 2>/dev/null && ! grep -q '\[GRUG CI\] CI mode active' "$SERVER_LOG"; then
    echo "==> The client exited before the server started the tests." >&2
    exit 1
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "==> Timed out after ${GRUG_CI_RUN_TIMEOUT:-300}s without an ALL n TESTS PASSED line." >&2
    exit 1
  fi
  sleep 2
done
