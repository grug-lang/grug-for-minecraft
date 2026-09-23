#!/usr/bin/env bash
# scripts/ci/discover-loaders.sh
#
# Emits one matrix entry per directory under loaders/. Used by the
# "discover" job in .github/workflows/build.yml so a new loader directory
# joins the build matrix automatically, without editing the workflow.
#
# JDK: hardcoded to 21 in build.yml, not discovered here. Every loader in
# this repo builds and runs under JDK 21 today, including the Alpha and
# Beta loaders via Ornithe (core and the StationAPI loader set
# sourceCompatibility 17, which a JDK 21 compiler handles fine as a
# bytecode target; nothing pins a loader to a different runtime JDK). If a
# future loader genuinely needs a different JDK, add per-loader detection
# back here (e.g. a .java-version file per loader dir) and thread a
# matching "jdk" field through the matrix and into build.yml's
# actions/setup-java step.
#
# Usage: scripts/ci/discover-loaders.sh
# In CI, appends `matrix=<json>` to $GITHUB_OUTPUT. Locally, just prints the
# discovered loaders and the JSON to stderr/stdout for inspection.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

names=()
shopt -s nullglob
for dir in loaders/*/; do
  names+=("$(basename "$dir")")
done
shopt -u nullglob

if [ "${#names[@]}" -eq 0 ]; then
  matrix="[]"
else
  joined=""
  for n in "${names[@]}"; do
    joined="${joined:+${joined},}\"${n}\""
  done
  matrix="[${joined}]"
fi

{
  echo "Discovered loaders:"
  for n in "${names[@]}"; do
    echo "  $n"
  done
} >&2

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "matrix=${matrix}" >> "$GITHUB_OUTPUT"
else
  echo "$matrix"
fi
