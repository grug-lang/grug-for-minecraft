#!/usr/bin/env bash
# .github/scripts/launch-production.sh <loader-dir-name> <game-dir> <jar>
#
# Installs the game and mod loader that <loader-dir-name> targets the way a player's launcher does,
# puts <jar> alone in <game-dir>/mods (beside the API mods that loader needs a player to have, such
# as StationAPI), and runs the game in the foreground. run-loader.sh's production phase prepares the
# test save, sets GRUG_CI and watches the log, exactly as it does for a development run.
#
# Nothing from the development tree reaches the game: no GRUG_MODS_DIR, no classpath entries, no
# repository mods. grug has to find, extract and load the mods bundled inside the jar.

set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <loader-dir-name> <game-dir> <jar>" >&2
  exit 2
fi
LOADER="$1"
GAME_DIR="$2"
JAR="$3"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOADER_DIR="${REPO_ROOT}/loaders/${LOADER}"

# The launcher's own state: the game versions, libraries, assets and JVMs it downloads. Shared by
# every loader and kept between runs, the way one launcher serves all of a player's instances.
CACHE_DIR="${GRUG_PRODUCTION_CACHE:-${HOME}/.cache/grug-production}"
MAIN_DIR="${CACHE_DIR}/main"

# portablemc installs Forge, Babric and any launcher profile like the vanilla launcher does. Pinned
# like every other version in this repository.
PORTABLEMC_VERSION="5.0.6"
VENV="${CACHE_DIR}/portablemc-${PORTABLEMC_VERSION}"

# A property from the loader's gradle.properties, which is where its versions are pinned.
property() {
  local value
  value=$(sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "${LOADER_DIR}/gradle.properties" | tr -d '\r' | head -n1)
  if [ -z "$value" ]; then
    echo "==> ERROR: ${LOADER_DIR}/gradle.properties declares no $1." >&2
    exit 1
  fi
  printf '%s' "$value"
}

portablemc() {
  if [ ! -x "${VENV}/bin/portablemc" ]; then
    python3 -m venv "$VENV"
    "${VENV}/bin/pip" install --quiet "portablemc==${PORTABLEMC_VERSION}"
  fi
  "${VENV}/bin/portablemc" --main-dir "$MAIN_DIR" "$@"
}

# Starts an installed version in GAME_DIR, replacing this shell so the watcher's process group is the
# game's.
start() {
  exec "${VENV}/bin/portablemc" --main-dir "$MAIN_DIR" start --mc-dir "$GAME_DIR" -u Player "$@"
}

# Writes a launcher profile under versions/, as an installer would.
install_profile() {
  local id="$1" url="$2"
  mkdir -p "${MAIN_DIR}/versions/${id}"
  curl --fail --silent --show-error --location "$url" -o "${MAIN_DIR}/versions/${id}/${id}.json"
}

# Mojang's profiles for Alpha and Beta boot through launchwrapper, which brings ASM 4. Fabric Loader
# refuses to start beside a second ASM, and the instance components Ornithe and Babric install for a
# player drop both libraries, so their launches here do too.
LEGACY_LIBRARIES=(--exclude-lib 'net.minecraft:launchwrapper:*' --exclude-lib 'org.ow2.asm:asm-all:*')

# Mojang's profiles also ask for Java 8 there, and grug's Alpha and Beta loaders need Java 17, as
# StationAPI does. A player of them picks a newer Java in their launcher, and so does this, with the
# Java the build runs on.
LEGACY_JAVA="${JAVA_HOME:+${JAVA_HOME}/bin/java}"
LEGACY_JAVA="${LEGACY_JAVA:-$(command -v java)}"
LEGACY_LIBRARIES+=(--jvm "$LEGACY_JAVA")

mkdir -p "${GAME_DIR}/mods" "$MAIN_DIR"
cp "$JAR" "${GAME_DIR}/mods/"

case "$LOADER" in
  1.20.6-forge)
    portablemc --version >/dev/null
    # A player's launcher picks a JVM the version accepts; this is the same choice.
    start --jvm-policy system-then-mojang "forge::$(property minecraft_version)-$(property forge_version)"
    ;;

  b1.7.3-stationapi)
    # StationAPI is the API this loader builds on, so a player of it has StationAPI installed.
    stationapi="$(property stationapi_version)"
    curl --fail --silent --show-error --location \
      "https://maven.glass-launcher.net/releases/net/modificationstation/StationAPI/${stationapi}/StationAPI-${stationapi}.jar" \
      -o "${GAME_DIR}/mods/StationAPI-${stationapi}.jar"
    portablemc --version >/dev/null
    start "${LEGACY_LIBRARIES[@]}" "babric::$(property loader_version)"
    ;;

  a1.1.2_01-ornithe | b1.7.3-ornithe)
    # What Ornithe's installer writes: its loader profile, which inherits from a copy of Mojang's
    # own version under a "-vanilla" name.
    game="$(property minecraft_version)"
    loader="$(property loader_version)"
    manifest="https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    vanilla_url=$(curl --fail --silent --show-error "$manifest" \
      | python3 -c "import json, sys; print(next(v['url'] for v in json.load(sys.stdin)['versions'] if v['id'] == sys.argv[1]))" "$game")
    install_profile "${game}-vanilla" "$vanilla_url"
    python3 - "${MAIN_DIR}/versions/${game}-vanilla/${game}-vanilla.json" "${game}-vanilla" <<'EOF'
import json, sys
path, new_id = sys.argv[1], sys.argv[2]
with open(path) as f:
    version = json.load(f)
version["id"] = new_id
with open(path, "w") as f:
    json.dump(version, f)
EOF
    profile="fabric-loader-${loader}-${game}-ornithe-gen2"
    install_profile "$profile" "https://meta.ornithemc.net/v3/versions/gen2/fabric-loader/${game}/${loader}/profile/json"
    portablemc --version >/dev/null
    start "${LEGACY_LIBRARIES[@]}" "$profile"
    ;;

  1.2.5-forge)
    # No launcher installs Forge 1.2.5, which is a jar mod, so this boots the loader's own pack-style
    # runtime with only the game's libraries on the classpath.
    cd "$LOADER_DIR"
    exec ./gradlew runProduction -Pgrug.productionGameDir="$GAME_DIR" --stacktrace --no-daemon
    ;;

  *)
    echo "==> ERROR: launch-production.sh does not know how to install ${LOADER}." >&2
    exit 1
    ;;
esac
