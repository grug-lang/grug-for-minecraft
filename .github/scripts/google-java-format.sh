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
readonly JAR_PATH="com/google/googlejavaformat/google-java-format/${VERSION}/${JAR_NAME}"
# Central first, then its mirror, because a single refusal from one host must not fail a required
# check: repo1 answered 404 for the jar on #1477's run 44691370056 and 200 minutes later (#1453).
readonly URLS=(
    "https://repo1.maven.org/maven2/${JAR_PATH}"
    "https://maven-central.storage-download.googleapis.com/maven2/${JAR_PATH}"
)

if [[ ! -f "${JAR}" ]]; then
    mkdir -p "${CACHE_DIR}"
    echo "Downloading google-java-format ${VERSION} to ${JAR}" >&2
    downloaded=0
    for url in "${URLS[@]}"; do
        if curl --fail --location --silent --show-error --output "${JAR}.tmp" "${url}"; then
            downloaded=1
            break
        fi
        echo "Could not download ${JAR_NAME} from ${url}" >&2
        rm -f "${JAR}.tmp"
    done
    if [[ "${downloaded}" != "1" ]]; then
        echo "Could not download ${JAR_NAME} from any host" >&2
        exit 1
    fi
    mv "${JAR}.tmp" "${JAR}"
fi

exec java -jar "${JAR}" "$@"
