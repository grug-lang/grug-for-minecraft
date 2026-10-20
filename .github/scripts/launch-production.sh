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

# The game profiles for Alpha and Beta, Mojang's and Ornithe's alike, boot through launchwrapper,
# which brings an old ASM. Fabric Loader refuses to start beside a second ASM, and the instance
# components Ornithe and Babric install for a player drop both libraries, so their launches here do
# too.
LEGACY_LIBRARIES=(--exclude-lib 'net.minecraft:launchwrapper:*' --exclude-lib 'org.ow2.asm:asm-all:*')

# Those profiles ask for Java 8 ("javaVersion": jre-legacy, major version 8), the oldest Java a
# player of the Ornithe layers may run, so the Ornithe launches take Mojang's runtime of that name.
# StationAPI needs Java 17, so a player of it picks a newer Java in their launcher, and that launch
# uses the Java the build runs on.
STATIONAPI_JAVA="${JAVA_HOME:+${JAVA_HOME}/bin/java}"
STATIONAPI_JAVA="${STATIONAPI_JAVA:-$(command -v java)}"

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
    # Babric's own profile carries LWJGL 2.9.4-babric.1, whose Linux natives link against a libjawt
    # symbol version only Java 8 has, while StationAPI needs Java 17. Babric's recommended install,
    # its Prism instance (github.com/babric/prism-instance), swaps in Legacy Fabric's LWJGL build,
    # which loads on any Java, so this launch does too.
    lwjgl="2.9.4+legacyfabric.17"
    lwjgl_dir="${CACHE_DIR}/lwjgl-${lwjgl}"
    lwjgl_maven="https://maven.legacyfabric.net/org/lwjgl/lwjgl"
    mkdir -p "$lwjgl_dir"
    for file in "lwjgl/${lwjgl}/lwjgl-${lwjgl}.jar" "lwjgl_util/${lwjgl}/lwjgl_util-${lwjgl}.jar" \
      "lwjgl-platform/${lwjgl}/lwjgl-platform-${lwjgl}-natives-linux.jar"; do
      jar="${lwjgl_dir}/${file##*/}"
      # The cache outlives the run, so a download cut off halfway must not land under the jar's name.
      if [ ! -f "$jar" ]; then
        curl --fail --silent --show-error --location "${lwjgl_maven}/${file}" -o "${jar}.part"
        mv "${jar}.part" "$jar"
      fi
    done
    portablemc --version >/dev/null
    start "${LEGACY_LIBRARIES[@]}" --jvm "$STATIONAPI_JAVA" --exclude-lib 'org.lwjgl.lwjgl:*:*' \
      --include-class "${lwjgl_dir}/lwjgl-${lwjgl}.jar" \
      --include-class "${lwjgl_dir}/lwjgl_util-${lwjgl}.jar" \
      --include-natives "${lwjgl_dir}/lwjgl-platform-${lwjgl}-natives-linux.jar" \
      "babric::$(property loader_version)"
    ;;

  a1.1.2_01-ornithe | b1.7.3-ornithe)
    # What Ornithe's installer (github.com/OrnitheMC/ornithe-installer-rs, src/actions/client.rs)
    # writes: the game's profile from Ornithe's own manifest, which swaps Mojang's LWJGL for one that
    # loads on any Java, under a "-gen2" name; and Ornithe's loader profile, inheriting from it, with
    # the libraries Ornithe adds to every instance (log4j, gson and others) appended.
    game="$(property minecraft_version)"
    loader="$(property loader_version)"
    vanilla="${game}-gen2"
    vanilla_url=$(curl --fail --silent --show-error "https://ornithemc.net/mc-versions/gen2/version_manifest.json" \
      | python3 -c "import json, sys; print(next(v['url'] for v in json.load(sys.stdin)['versions'] if v['id'] == sys.argv[1]))" "$game")
    install_profile "$vanilla" "$vanilla_url"
    profile="fabric-loader-${loader}-${game}-ornithe-gen2"
    install_profile "$profile" "https://meta.ornithemc.net/v3/versions/gen2/fabric-loader/${game}/${loader}/profile/json"
    curl --fail --silent --show-error "https://meta.ornithemc.net/v3/versions/gen2/libraries/${game}" \
      -o "${MAIN_DIR}/versions/${profile}/libraries.json"
    python3 - "${MAIN_DIR}/versions" "$vanilla" "$profile" <<'EOF'
import json, os, sys
versions, vanilla, profile = sys.argv[1:]
def rewrite(name, change):
    path = os.path.join(versions, name, name + ".json")
    with open(path) as f:
        version = json.load(f)
    change(version)
    with open(path, "w") as f:
        json.dump(version, f)
rewrite(vanilla, lambda version: version.update(id=vanilla))
with open(os.path.join(versions, profile, "libraries.json")) as f:
    upgrades = json.load(f)
def loader_profile(version):
    version["inheritsFrom"] = vanilla
    version["libraries"] += upgrades
rewrite(profile, loader_profile)
EOF
    portablemc --version >/dev/null
    start "${LEGACY_LIBRARIES[@]}" --jvm-policy mojang --exclude-lib 'org.ow2.asm:*:7.0' "$profile"
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
