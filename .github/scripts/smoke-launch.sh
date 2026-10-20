#!/usr/bin/env bash
# .github/scripts/smoke-launch.sh <recipe> <jar> <work-dir>
#
# Boots a game grug has no layer for with <jar> as its only mod, installed the way a player's
# launcher installs it, and requires what #493 promises there. Each recipe is named by a game in
# universal_jar.py's GAMES, which picks one game per mechanism a mod loader reads the jar by, and is
# listed by `universal_jar.py smoke-matrix`.
#
# Where grug's code runs (Forge, NeoForge and the Fabric family), the game has to stop with grug's
# own error, "grug has no layer for ...", through its loader's error path: not with another error
# first, and without grug starting. Where it does not (legacy FML and ModLoader), the game has to
# load to its first screen with grug inactive: grug's boot marker cannot be the signal there, so
# the recipe waits on a line its game or loader logs once loading is over, then for the log to go
# quiet with the game still running, and fails on a crash, a crash report, a loader complaint about
# the jar, or any sign that grug started. Either way the screen is captured at the end for a person
# to look at.
#
# <work-dir> receives game/ (the game directory), launch.log and title.png.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source-path=SCRIPTDIR source=launcher.sh
source "${REPO_ROOT}/.github/scripts/launcher.sh"

# The original ModLoader for Beta 1.7.3, as Risugami released it, kept by the ModloaderFix project.
MODLOADER_URL="https://raw.githubusercontent.com/coffeenotfound/ModloaderFix-b1.7.3/db5e088abf3922a7c89dbc9d97394ef7c5bc5c1c/jars/ModLoader%20b1.7.3%20Original.zip"
MODLOADER_SHA256="78bc1107a2ae78334d1086c7f372601c141b53345f23ce73931ef318df5cf83e"

# Forge's and NeoForge's early loading window opens its GL window against a 10-second deadline and,
# when a headless display misses it, blocks forever in a message box. run-loader.sh switches it off
# for the same reason; it only decides whether a loading screen is drawn.
no_early_window() {
  mkdir -p "${GAME_DIR}/config"
  printf 'earlyWindowControl = false\n' >"${GAME_DIR}/config/fml.toml"
}

# Patches ModLoader into a copy of the vanilla b1.7.3 client jar, the way a player installed it:
# ModLoader's classes replace the game's, and META-INF goes, since its signature no longer matches.
install_modloader() {
  local id="$1" vanilla="${MAIN_DIR}/versions/b1.7.3" dir="${MAIN_DIR}/versions/$1"
  local zip="${CACHE_DIR}/modloader-b1.7.3-original.zip"
  cached_download "$MODLOADER_URL" "$zip"
  echo "${MODLOADER_SHA256}  ${zip}" | sha256sum --check --quiet
  mkdir -p "$dir"
  python3 - "$vanilla/b1.7.3.json" "$vanilla/b1.7.3.jar" "$zip" "$id" "$dir" <<'EOF'
import json, sys, zipfile
profile, vanilla, modloader, id, out = sys.argv[1:]
with zipfile.ZipFile(modloader) as patch:
    added = {name: patch.read(name) for name in patch.namelist()}
with zipfile.ZipFile(vanilla) as game, zipfile.ZipFile(f"{out}/{id}.jar", "w") as jar:
    for info in game.infolist():
        if not info.filename.startswith("META-INF/") and info.filename not in added:
            jar.writestr(info, game.read(info))
    for name, data in added.items():
        jar.writestr(name, data)
with open(profile) as f:
    version = json.load(f)
version["id"] = id
# No download entry, so the launcher runs this patched jar rather than fetching Mojang's again.
version.get("downloads", {}).pop("client", None)
with open(f"{out}/{id}.json", "w") as f:
    json.dump(version, f)
EOF
}

# What `launch` runs in the background, in a process group of its own: installs the recipe's game
# and replaces itself with it.
launch() {
  local recipe="$1"
  case "$recipe" in
    forge-1.21.1) no_early_window && start --jvm-policy mojang "forge::1.21.1-52.1.0" ;;
    forge-1.16.5) start --jvm-policy mojang "forge::1.16.5-36.2.34" ;;
    forge-1.12.2) start --jvm-policy mojang "forge::1.12.2-14.23.5.2859" ;;
    forge-1.6.4) start --jvm-policy mojang "forge::1.6.4-9.11.1.1345" ;;
    neoforge-20.4) no_early_window && start --jvm-policy mojang "neoforge::20.4.253" ;;
    neoforge-21.1) no_early_window && start --jvm-policy mojang "neoforge::21.1.264" ;;
    babric-b1.7.3)
      # Babric as launch-production.sh installs it for StationAPI, without StationAPI.
      babric_lwjgl
      start "${LEGACY_LIBRARIES[@]}" --jvm-policy system-then-mojang "${BABRIC_LWJGL[@]}" "babric::0.17.3"
      ;;
    modloader-b1.7.3)
      portablemc start --dry b1.7.3 >/dev/null
      install_modloader b1.7.3-modloader
      start --jvm-policy mojang b1.7.3-modloader
      ;;
    *)
      echo "==> ERROR: smoke-launch.sh has no recipe ${recipe}." >&2
      exit 2
      ;;
  esac
}

if [ "${1:-}" = --launch ]; then
  GAME_DIR="$3"
  launch "$2"
fi

if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <recipe> <jar> <work-dir>" >&2
  exit 2
fi
RECIPE="$1"
JAR="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
WORK="$3"
mkdir -p "$WORK"
WORK="$(cd "$WORK" && pwd)"
GAME_DIR="${WORK}/game"
LOG="${WORK}/launch.log"

# EXPECT is error or inert. For an inert game, READY is the line that says loading is over, read
# from READY_FILE (the launch log unless the loader keeps a log of its own), and COMPLAINTS are its
# loader's words for a jar it refuses, skips or warns about, which must not appear.
READY_FILE="$LOG"
COMPLAINTS=''
case "$RECIPE" in
  forge-1.21.1 | forge-1.16.5 | neoforge-20.4 | neoforge-21.1 | babric-b1.7.3)
    EXPECT=error
    ;;
  forge-1.12.2)
    EXPECT=inert
    READY='Forge Mod Loader has successfully loaded'
    # ModType keeps FML from reading the jar's classes at all.
    COMPLAINTS='failed to read properly|There was a problem reading the entry'
    ;;
  forge-1.6.4)
    EXPECT=inert
    # Its ASM 4 cannot read the jar's first class, so FML ignores the jar with a warning: the
    # designed outcome, which a crash or a crash report would contradict.
    READY='Forge Mod Loader has successfully loaded'
    ;;
  modloader-b1.7.3)
    EXPECT=inert
    READY_FILE="${GAME_DIR}/ModLoader.txt"
    READY='Done\.'
    COMPLAINTS='Mod Loaded|Failed to load mod'
    ;;
  *)
    echo "==> ERROR: smoke-launch.sh has no recipe ${RECIPE}." >&2
    exit 2
    ;;
esac
# grug's error, from core's GrugUnsupportedGame.
GRUG_ERROR='grug has no layer for'
# A crash on any version: the report every version writes, and the ways a thread dies before one.
CRASHES='---- Minecraft Crash Report ----|Exception in thread "main"|Exception in thread "Minecraft main thread"|Could not execute entrypoint stage|Broken grug invariant'
# grug's own log lines; GrugModsDirectory also creates grug_mods/ in the game directory once a layer
# starts, which problems() checks.
GRUG_STARTED='\[GRUG'

rm -rf "$GAME_DIR"
mkdir -p "${GAME_DIR}/mods" "$MAIN_DIR"
cp "$JAR" "${GAME_DIR}/mods/"
: >"$LOG"

setsid "$0" --launch "$RECIPE" "$GAME_DIR" >"$LOG" 2>&1 &
GAME_PID=$!
stop_game() { kill -- "-$GAME_PID" 2>/dev/null || true; }
trap stop_game EXIT

# Problems in the logs and the game directory, printed one per line.
problems() {
  # A game that stops with grug's error crashes and writes a crash report on purpose, and the
  # crash's first lines can reach the log before the line with grug's message, so for it only an
  # exit without that message is a failure, which the wait below reports.
  if [ "$EXPECT" = inert ]; then
    grep -E -m1 "$CRASHES" "$LOG" | sed 's/^/crashed: /' || true
    if compgen -G "${GAME_DIR}/crash-reports/*" >/dev/null; then
      echo "crashed: $(ls "${GAME_DIR}/crash-reports")"
    fi
  fi
  if [ -n "$COMPLAINTS" ]; then
    grep -E -m3 "$COMPLAINTS" "$LOG" "$READY_FILE" 2>/dev/null | sed 's/^/the loader complained: /' || true
  fi
  grep -E -m3 "$GRUG_STARTED" "$LOG" | sed 's/^/grug started: /' || true
  if [ -d "${GAME_DIR}/grug_mods" ]; then echo "grug started: it created grug_mods/"; fi
}

fail() {
  echo "==> ERROR: ${RECIPE}: $1" >&2
  tail -n 60 "$LOG" >&2
  exit 1
}

deadline=$((SECONDS + ${GRUG_SMOKE_TIMEOUT:-900}))

# Waits until the log has been quiet for $1 seconds, failing on a problem, and, for an inert game, if
# the game exits. A cold cache downloads the game, its libraries and its assets first.
settle() {
  local quiet_since=$SECONDS size=-1 current settle_deadline=$((SECONDS + 180))
  while [ $((SECONDS - quiet_since)) -lt "$1" ]; do
    current=$(stat -c %s "$LOG")
    if [ "$current" != "$size" ]; then size=$current quiet_since=$SECONDS; fi
    if [ -n "$(problems)" ]; then fail "$(problems)"; fi
    if ! kill -0 "$GAME_PID" 2>/dev/null; then
      if [ "$EXPECT" = inert ]; then fail "the game exited after loading ended"; fi
      return
    fi
    if [ "$SECONDS" -ge "$settle_deadline" ]; then fail "the log never went quiet"; fi
    sleep 1
  done
}

if [ "$EXPECT" = error ]; then
  until grep -q -F "$GRUG_ERROR" "$LOG"; do
    if [ -n "$(problems)" ]; then fail "$(problems)"; fi
    if ! kill -0 "$GAME_PID" 2>/dev/null; then fail "the game exited without grug's error"; fi
    if [ "$SECONDS" -ge "$deadline" ]; then fail "grug's error did not appear within the timeout"; fi
    sleep 2
  done
  echo "==> ${RECIPE}: stopped with grug's error: $(grep -m1 -o "${GRUG_ERROR}.*" "$LOG" | head -c 300)"
  # Gives the loader time to put its error screen or window up before the capture.
  settle 5
  import -window root "${WORK}/title.png"
  echo "==> ${RECIPE}: see ${WORK}/title.png"
  exit 0
fi

until grep -E -q "$READY" "$READY_FILE" 2>/dev/null; do
  if [ -n "$(problems)" ]; then fail "$(problems)"; fi
  if ! kill -0 "$GAME_PID" 2>/dev/null; then fail "the game exited before loading ended"; fi
  if [ "$SECONDS" -ge "$deadline" ]; then fail "loading did not end within the timeout"; fi
  sleep 2
done
echo "==> ${RECIPE}: loading ended ($(grep -E -m1 -o "$READY" "$READY_FILE"))"

# Waits for the game to settle on its first screen. A loader that rejects the jar does so around this
# point, with an error screen, a crash report or a warning.
settle 15

import -window root "${WORK}/title.png"
echo "==> ${RECIPE}: reached its first screen with grug inactive; see ${WORK}/title.png"
