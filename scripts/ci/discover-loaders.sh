#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

entries=()
shopt -s nullglob
for dir in loaders/*/; do
  name="$(basename "$dir")"
  # All loaders run on Java 21. The SHA-1 signature verification
  # issue on older Minecraft jars is bypassed via Gradle properties.
  jdk="21"
  entries+=("{\"dir\":\"${name}\",\"jdk\":\"${jdk}\"}")
done
shopt -u nullglob

if [ "${#entries[@]}" -eq 0 ]; then
  matrix="[]"
else
  joined="$(IFS=,; echo "${entries[*]}")"
  matrix="[${joined}]"
fi

{
  echo "Discovered loaders:"
  for e in "${entries[@]}"; do
    echo "  $e"
  done
} >&2

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "matrix=${matrix}" >> "$GITHUB_OUTPUT"
else
  echo "$matrix"
fi
