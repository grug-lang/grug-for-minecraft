#!/usr/bin/env bash
# .github/scripts/launch-production.sh <loader-dir-name> <game-dir> <jar>
#
# Installs the game and mod loader that <loader-dir-name> targets the way a player's launcher does,
# puts <jar> alone in <game-dir>/mods (beside the API mods that loader needs a player to have, such
# as StationAPI or OSL), and runs the game in the foreground. run-loader.sh's production phase prepares the
# test save, sets GRUG_CI and watches the log, exactly as it does for a development run.
#
# Nothing from the development tree reaches the game: no GRUG_MODS_DIR, no classpath entries, no
# repository mods. grug has to find, extract and load the mods bundled inside the jar.
#
# GRUG_BESIDE=jei installs JEI beside the jar too, for build.yml's coexistence job (#501): grug next
# to a non-grug mod that reads every registered item, grug's included. Only 1.20.6-forge has it.

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

# shellcheck source-path=SCRIPTDIR source=launcher.sh
source "${REPO_ROOT}/.github/scripts/launcher.sh"

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

# The Alpha and Beta game profiles ask for Java 8 ("javaVersion": jre-legacy, major version 8), the
# oldest Java a player of the Ornithe layers may run, so the Ornithe launches take Mojang's runtime
# of that name.
# StationAPI needs Java 17, so a player of it picks a newer Java in their launcher, and that launch
# uses the Java the build runs on.
STATIONAPI_JAVA="${JAVA_HOME:+${JAVA_HOME}/bin/java}"
STATIONAPI_JAVA="${STATIONAPI_JAVA:-$(command -v java)}"

mkdir -p "${GAME_DIR}/mods" "$MAIN_DIR"
cp "$JAR" "${GAME_DIR}/mods/"

# The jar leaves the coverage fixtures out (#509), so the suite finds them where a player puts a mod
# they downloaded: in grug_mods/. The bundled mods ship turned off (#335), so the suite turns on the
# ones it tests the way a player does, by their state in the game directory, and the first run
# installs them from the jar.
mkdir -p "${GAME_DIR}/grug_mods"
cp -r "${REPO_ROOT}/mods/coverage" "${GAME_DIR}/grug_mods/"
python3 - "${REPO_ROOT}/mods" "${GAME_DIR}/grug_mods_state.json" <<'PY'
import json, pathlib, sys
mods = sorted(p.name for p in pathlib.Path(sys.argv[1]).iterdir() if p.is_dir() and p.name != "coverage")
pathlib.Path(sys.argv[2]).write_text(json.dumps({"mods": {name: True for name in mods}}) + "\n")
PY

# A mod's primary file for one version and mod loader, from Modrinth as a player downloads it. The
# mod loader matters: JEI publishes its Forge, NeoForge and Fabric builds under one version number,
# and Forge skips the others without a word (#501).
modrinth() {
  local project="$1" version="$2" loader="$3" url
  url=$(curl --fail --silent --show-error --user-agent "grug-lang/grug-for-minecraft CI" \
    "https://api.modrinth.com/v2/project/${project}/version" \
    | python3 -c "import json, sys; print(next(f['url'] for v in json.load(sys.stdin) if v['version_number'] == sys.argv[1] and sys.argv[2] in v['loaders'] for f in v['files'] if f['primary']))" "$version" "$loader")
  curl --fail --silent --show-error --location "$url" -o "${GAME_DIR}/mods/${project}-${version}.jar"
}

BESIDE="${GRUG_BESIDE:-}"
if [ -n "$BESIDE" ] && { [ "$BESIDE" != jei ] || [ "$LOADER" != 1.20.6-forge ]; }; then
  echo "==> ERROR: GRUG_BESIDE='${BESIDE}' is not a mod launch-production.sh installs beside ${LOADER}; only jei on 1.20.6-forge is." >&2
  exit 1
fi

case "$LOADER" in
  1.20.6-forge)
    if [ "$BESIDE" = jei ]; then
      jei="$(property jei_version)"
      modrinth jei "$jei" forge
    fi
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
    babric_lwjgl
    portablemc --version >/dev/null
    start "${LEGACY_LIBRARIES[@]}" --jvm "$STATIONAPI_JAVA" "${BABRIC_LWJGL[@]}" \
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
    # OSL, which a player of any OSL mod installs beside it (Ornithe's installer says so once it is
    # done), from Modrinth like they would. The Ornithe layers depend on it rather than nesting it.
    osl="$(property osl_version)"
    modrinth osl "$osl" ornithe
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
