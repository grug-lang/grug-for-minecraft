# shellcheck shell=bash disable=SC2034  # its variables are for the scripts that source it
# .github/scripts/launcher.sh, sourced by launch-production.sh and smoke-launch.sh.
#
# Installs games the way a player's launcher does, through portablemc, and starts them. The caller
# sets GAME_DIR, the game directory a launch uses, before calling start.

# The launcher's own state: the game versions, libraries, assets and JVMs it downloads. Shared by
# every launch and kept between runs, the way one launcher serves all of a player's instances.
CACHE_DIR="${GRUG_PRODUCTION_CACHE:-${HOME}/.cache/grug-production}"
MAIN_DIR="${CACHE_DIR}/main"

# portablemc installs Forge, NeoForge, Babric and any launcher profile like the vanilla launcher
# does. Pinned like every other version in this repository.
PORTABLEMC_VERSION="5.0.6"
VENV="${CACHE_DIR}/portablemc-${PORTABLEMC_VERSION}"

# A virtual environment keeps its programs in bin/ on Linux and Scripts/ on Windows, where they end in
# .exe, and python3 there can be a Microsoft Store stub that opens the store instead of running
# anything. Git Bash on a Windows runner reports OSTYPE=msys.
case "${OSTYPE:-}" in
  msys* | cygwin*)
    PYTHON=python
    VENV_BIN="${VENV}/Scripts"
    EXE=.exe
    ;;
  *)
    PYTHON=python3
    VENV_BIN="${VENV}/bin"
    EXE=
    ;;
esac

install_portablemc() {
  if [ ! -x "${VENV_BIN}/portablemc${EXE}" ]; then
    "$PYTHON" -m venv "$VENV"
    "${VENV_BIN}/pip${EXE}" install --quiet "portablemc==${PORTABLEMC_VERSION}"
  fi
}

portablemc() {
  install_portablemc
  "${VENV_BIN}/portablemc${EXE}" --main-dir "$MAIN_DIR" "$@"
}

# Starts a version in GAME_DIR, installing it first if needed, and replaces this shell so the
# watcher's process group is the game's.
start() {
  install_portablemc
  exec "${VENV_BIN}/portablemc${EXE}" --main-dir "$MAIN_DIR" start --mc-dir "$GAME_DIR" -u Player "$@"
}

# Writes a launcher profile under versions/, as an installer would.
install_profile() {
  local id="$1" url="$2"
  mkdir -p "${MAIN_DIR}/versions/${id}"
  curl --fail --silent --show-error --location "$url" -o "${MAIN_DIR}/versions/${id}/${id}.json"
}

# Downloads a file into the cache once. The cache outlives the run, so a download cut off halfway
# must not land under the file's name.
cached_download() {
  local url="$1" file="$2"
  if [ ! -f "$file" ]; then
    mkdir -p "$(dirname "$file")"
    curl --fail --silent --show-error --location --retry 3 --retry-delay 10 --retry-all-errors "$url" -o "${file}.part"
    mv "${file}.part" "$file"
  fi
}

# The game profiles for Alpha and Beta, Mojang's and Ornithe's alike, boot through launchwrapper,
# which brings an old ASM. Fabric Loader refuses to start beside a second ASM, and the instance
# components Ornithe and Babric install for a player drop both libraries, so their launches here do
# too.
LEGACY_LIBRARIES=(--exclude-lib 'net.minecraft:launchwrapper:*' --exclude-lib 'org.ow2.asm:asm-all:*')

# Babric's own profile carries LWJGL 2.9.4-babric.1, whose Linux natives link against a libjawt
# symbol version only Java 8 has, while StationAPI needs Java 17. Babric's recommended install, its
# Prism instance (github.com/babric/prism-instance), swaps in Legacy Fabric's LWJGL build, which
# loads on any Java. Sets BABRIC_LWJGL to the start arguments that make that swap.
babric_lwjgl() {
  local lwjgl="2.9.4+legacyfabric.17"
  local lwjgl_dir="${CACHE_DIR}/lwjgl-${lwjgl}"
  local lwjgl_maven="https://maven.legacyfabric.net/org/lwjgl/lwjgl"
  local file
  for file in "lwjgl/${lwjgl}/lwjgl-${lwjgl}.jar" "lwjgl_util/${lwjgl}/lwjgl_util-${lwjgl}.jar" \
    "lwjgl-platform/${lwjgl}/lwjgl-platform-${lwjgl}-natives-linux.jar"; do
    cached_download "${lwjgl_maven}/${file}" "${lwjgl_dir}/${file##*/}"
  done
  BABRIC_LWJGL=(--exclude-lib 'org.lwjgl.lwjgl:*:*'
    --include-class "${lwjgl_dir}/lwjgl-${lwjgl}.jar"
    --include-class "${lwjgl_dir}/lwjgl_util-${lwjgl}.jar"
    --include-natives "${lwjgl_dir}/lwjgl-platform-${lwjgl}-natives-linux.jar")
}
