#!/usr/bin/env bash
# Runs google-java-format with a pinned version and a cached jar.
#
# pre-commit passes the staged Java paths and CI passes every tracked Java file, so this only has
# to resolve the jar and forward its arguments. The jar is cached under XDG_CACHE_HOME so repeated
# runs stay offline; CI caches that directory too.
set -euo pipefail

readonly VERSION="1.36.1"
readonly JAR_NAME="google-java-format-${VERSION}-all-deps.jar"
readonly CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/grug-google-java-format"
readonly JAR="${CACHE_DIR}/${JAR_NAME}"
readonly URL="https://repo1.maven.org/maven2/com/google/googlejavaformat/google-java-format/${VERSION}/${JAR_NAME}"

if [[ ! -f "${JAR}" ]]; then
    mkdir -p "${CACHE_DIR}"
    echo "Downloading google-java-format ${VERSION} to ${JAR}" >&2
    curl --fail --location --silent --show-error --output "${JAR}.tmp" "${URL}"
    mv "${JAR}.tmp" "${JAR}"
fi

exec java -jar "${JAR}" "$@"
