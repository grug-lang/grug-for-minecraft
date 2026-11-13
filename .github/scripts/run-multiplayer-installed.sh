#!/usr/bin/env bash
# .github/scripts/run-multiplayer-installed.sh <loader-dir-name>
#
# The multiplayer path from the universal jar alone, on both sides (#1065): a dedicated server that
# a player would set up, with the jar in its mods folder, and a client installed the way
# launch-production.sh installs one, with the same jar, which joins it through GRUG_CI_CONNECT. It is
# a boot-and-join check and not the suite (the maintainer's decision of 2026-11-03 in #1065): the
# development-run legs of run-multiplayer.sh already prove the behaviour, and this one is about the
# jar. It is done when the server has compiled the jar's grug files and has logged the client in,
# which is what GRUG_MULTIPLAYER_BOOT_ONLY=1 used to mean in run-multiplayer.sh (#166), kept here
# as the only mode. Neither side runs with GRUG_CI, so no test runs and no coverage is recorded.
#
# Which loaders: the two Ornithe loaders, whose server Ornithe's installer sets up. 1.20.6-forge's
# universal jar does not load on a Forge server yet (#1314), and b1.7.3-stationapi and 1.2.5-forge
# have no server a player installs from a launcher or an installer, so they stay on
# run-multiplayer.sh's development server.
#
# Needs GRUG_UNIVERSAL_JAR, the jar .github/scripts/universal_jar.py assembled, and a display for the
# client, so run it under xvfb-run.

set -uo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <loader-dir-name>" >&2
  exit 2
fi
LOADER="$1"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

LOADER_DIR="loaders/${LOADER}"
case "$LOADER" in
  a1.1.2_01-ornithe | b1.7.3-ornithe) ;;
  *)
    echo "No installed dedicated server for ${LOADER}: only a1.1.2_01-ornithe and b1.7.3-ornithe have one." >&2
    exit 2
    ;;
esac
if [ -z "${GRUG_UNIVERSAL_JAR:-}" ] || [ ! -s "$GRUG_UNIVERSAL_JAR" ]; then
  echo "GRUG_UNIVERSAL_JAR must name the universal jar." >&2
  exit 2
fi
if [ -z "${DISPLAY:-}" ]; then
  echo "DISPLAY is not set. Start an X server first, e.g. run under xvfb-run." >&2
  exit 2
fi
JAR="$(cd "$(dirname "$GRUG_UNIVERSAL_JAR")" && pwd)/$(basename "$GRUG_UNIVERSAL_JAR")"

WORK="${REPO_ROOT}/${LOADER_DIR}/build/installed-multiplayer"
SERVER_DIR="${WORK}/server"
CLIENT_DIR="${WORK}/client"
SERVER_LOG="${WORK}/server.log"
CLIENT_LOG="${WORK}/client.log"
PORT=25565
READY_RE='Done( \(.*\))?! For help, type "help"'
ORNITHE_INSTALLER_VERSION=0.5.4
# What the Ornithe loaders' mod loader prints once grug has compiled its files in the server.
LOADED_RE='Compiled [0-9]+ grug files successfully'
crash_re='Uncaught exception in thread "Server thread"|Exception in thread "main"|Uncaught exception in thread "Minecraft main thread"|Exception in thread "Minecraft main thread"|Game crashed! Crash report saved to|Broken grug invariant|Failed to start the minecraft server|panicked at .*\.rs:[0-9]+:[0-9]+'

# A property from the loader's gradle.properties, which is where its versions are pinned.
property() {
  local value
  value=$(sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "${LOADER_DIR}/gradle.properties" | tr -d '\r' | head -n1)
  if [ -z "$value" ]; then
    echo "==> ${LOADER_DIR}/gradle.properties declares no $1." >&2
    return 1
  fi
  printf '%s' "$value"
}

rm -rf "$WORK"
mkdir -p "$SERVER_DIR" "$CLIENT_DIR"

server_pid=""
client_pid=""
server_tail_pid=""
client_tail_pid=""
cleanup() {
  for pid in "$server_tail_pid" "$client_tail_pid"; do
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

# The test save's world, so the server opens a world the loader's tests know. The folder's name is
# the level name the server is told.
SAVE_ZIP=$(tr -d ' \n\r' < "${LOADER_DIR}/test-save.txt")
if ! python3 -m zipfile -e "test-saves/${SAVE_ZIP}" "${SERVER_DIR}/"; then
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
  echo "The test save holds no world folder, so the server has no level name." >&2
  exit 1
fi
echo "eula=true" > "${SERVER_DIR}/eula.txt"
cat > "${SERVER_DIR}/server.properties" <<PROPS
online-mode=false
level-name=${LEVEL_NAME}
server-port=${PORT}
view-distance=4
simulation-distance=4
max-tick-time=-1
spawn-protection=0
PROPS
# The old servers spawn mobs and kick a player they see floating, which the boot has no use for.
printf 'spawn-monsters=false\nspawn-animals=false\nallow-flight=true\n' >> "${SERVER_DIR}/server.properties"

# The coverage fixtures and the bundled mods' state, where a player puts a mod they downloaded and
# where the first run installs the bundled mods from the jar, as launch-production.sh does.
mkdir -p "${SERVER_DIR}/mods" "${SERVER_DIR}/grug_mods"
cp "$JAR" "${SERVER_DIR}/mods/grug.jar"
cp -r mods/coverage "${SERVER_DIR}/grug_mods/"
python3 - mods "${SERVER_DIR}/grug_mods_state.json" <<'PY'
import json, pathlib, sys
mods = sorted(p.name for p in pathlib.Path(sys.argv[1]).iterdir() if p.is_dir() and p.name != "coverage")
pathlib.Path(sys.argv[2]).write_text(json.dumps({"mods": {name: {"on": True} for name in mods}}) + "\n")
PY

install_ornithe_server() {
  local installer="${WORK}/ornithe-installer-cli" server_version loader_version osl
  server_version=$(tr -d ' \n\r' < "${LOADER_DIR}/server-version.txt") || return 1
  loader_version=$(property loader_version) || return 1
  osl=$(property osl_version) || return 1
  curl --fail --silent --show-error --location \
    "https://maven.ornithemc.net/releases/net/ornithemc/ornithe-installer-rs/ornithe-installer-rs-linux-x86_64/${ORNITHE_INSTALLER_VERSION}/ornithe-installer-rs-linux-x86_64-${ORNITHE_INSTALLER_VERSION}-cli" \
    -o "$installer" || return 1
  chmod +x "$installer"
  echo "==> Installing the Ornithe server ${server_version} with loader ${loader_version}"
  "$installer" server -m "$server_version" --loader-version "$loader_version" -d "$SERVER_DIR" || return 1
  if [ ! -s "${SERVER_DIR}/server.jar" ] || [ ! -s "${SERVER_DIR}/fabric-server-launch.jar" ]; then
    echo "==> The Ornithe installer left no usable server in ${SERVER_DIR}." >&2
    return 1
  fi
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
  echo "==> Starting the Ornithe server from the universal jar"
  (cd "$SERVER_DIR" && exec setsid java -jar fabric-server-launch.jar nogui) >>"$SERVER_LOG" 2>&1 &
  server_pid=$!
}

: > "$SERVER_LOG"
: > "$CLIENT_LOG"
tail -n +1 -F "$SERVER_LOG" 2>/dev/null | sed -u 's/^/[server] /' &
server_tail_pid=$!

start_seconds=$SECONDS
install_ornithe_server >>"$SERVER_LOG" 2>&1 || { echo "==> Installing the dedicated server of ${LOADER} failed." >&2; exit 1; }
echo "==> The server install took $((SECONDS - start_seconds)) s."

server_deadline=$((SECONDS + ${GRUG_CI_SERVER_TIMEOUT:-300}))
while ! grep -qE "$READY_RE" "$SERVER_LOG"; do
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "==> The dedicated server process exited before it was ready." >&2
    exit 1
  fi
  if line=$(grep -m1 -E "$crash_re" "$SERVER_LOG"); then
    echo "==> The dedicated server crashed: ${line}" >&2
    exit 1
  fi
  if [ "$SECONDS" -ge "$server_deadline" ]; then
    echo "==> The dedicated server was not ready after ${GRUG_CI_SERVER_TIMEOUT:-300}s." >&2
    exit 1
  fi
  sleep 2
done
echo "==> The server is ready after $((SECONDS - start_seconds)) s. Installing the client the way a player does and joining 127.0.0.1:${PORT}"

printf 'onboardAccessibility:false\nnarrator:0\n' > "${CLIENT_DIR}/options.txt"
client_start=$SECONDS
GRUG_CI_CONNECT="127.0.0.1:${PORT}" setsid "${REPO_ROOT}/.github/scripts/launch-production.sh" "$LOADER" "$CLIENT_DIR" "$JAR" >"$CLIENT_LOG" 2>&1 &
client_pid=$!
tail -n +1 -F "$CLIENT_LOG" 2>/dev/null | sed -u 's/^/[client] /' &
client_tail_pid=$!

# Both sides run the same jar, so the server compiling its files and logging a player in is the
# whole check: the client's own install and boot has to work for the player to arrive.
deadline=$((SECONDS + ${GRUG_CI_RUN_TIMEOUT:-1200}))
while :; do
  if grep -qE "$LOADED_RE" "$SERVER_LOG" \
    && grep -qE ' logged in' "$SERVER_LOG"; then
    echo "==> grug loaded in the dedicated server from the universal jar, and a client installed with the same jar joined it after $((SECONDS - client_start)) s of client install and boot."
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
    echo "==> The dedicated server exited before a client joined." >&2
    exit 1
  fi
  if ! kill -0 "$client_pid" 2>/dev/null; then
    echo "==> The client exited before it joined the server." >&2
    exit 1
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "==> No client joined the server within ${GRUG_CI_RUN_TIMEOUT:-1200}s." >&2
    exit 1
  fi
  sleep 2
done
