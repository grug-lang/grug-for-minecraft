#!/usr/bin/env bash
# scripts/ci/require-jei-log.sh <log-dir>
#
# Fails unless the run's logs show JEI starting without an error. The job that installs JEI beside
# grug always calls this: JEI builds its ingredient list from every registered item before it logs
# "Starting JEI GUI", so an item of grug's that JEI cannot read shows up as a mezz.jei error line or
# as JEI never starting at all, and either one fails the job rather than being read out of a log.
#
# It reads the whole log set of <log-dir>, latest.log and every rotated *.log.gz, and not latest.log
# alone: the game rolls latest.log when the date changes, so a run that starts before midnight UTC and
# finishes after it writes JEI's startup lines into the rotated file, and a check of latest.log alone
# then fails a run whose tests all passed (#1775). run-loader.sh removes the game directory before a
# production launch, so every file under logs/ belongs to that one run and reading the whole set
# cannot accept a previous session's line.
#
# A relative <log-dir> is resolved from the repository root, so the workflow's path and a hand-run
# reproduction name the same directory.
#
# Prints the reason and exits 1 on a failure, and exits 2 on a usage error. Ok is silent.

set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <log-dir>" >&2
  exit 2
fi

LOG_DIR="$1"

# The script's own directory and the repository root, resolved before the cd, because a relative
# <log-dir> has to mean the same directory however this is invoked (#1726).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "$REPO_ROOT"

if [ ! -d "$LOG_DIR" ]; then
  echo "==> ERROR: the log set is missing: $LOG_DIR is not a directory"
  exit 1
fi

# latest.log and the roll's own files, in the order the game wrote them: latest.log holds the newest
# part of the run and each rotated file the part before it.
logs=()
if [ -e "$LOG_DIR/latest.log" ]; then
  logs+=("$LOG_DIR/latest.log")
fi
shopt -s nullglob
for rotated in "$LOG_DIR"/*.log.gz; do
  logs+=("$rotated")
done
shopt -u nullglob

if [ "${#logs[@]}" -eq 0 ]; then
  echo "==> ERROR: the log set is missing: $LOG_DIR holds no latest.log and no *.log.gz"
  exit 1
fi

logs_csv="$(printf '%s, ' "${logs[@]}")"
logs_csv="${logs_csv%, }"

# One file of the set as text. The roll writes .log.gz, so a rotated file is inflated and latest.log
# is read as it is; no call has to know which kind it got.
contents() {
  case "$1" in
    *.gz) gzip -cd -- "$1" ;;
    *) cat -- "$1" ;;
  esac
}

# The host function logging that shape, which the same expression as before the check moved here
# matches (#1775).
ERROR_RE='/(ERROR|FATAL)\] \[mezz\.jei'

started=false
errors=""
for log in "${logs[@]}"; do
  # The match goes to /dev/null rather than through grep -q, which stops reading at the first match:
  # the reader on the other side of the pipe is then killed by SIGPIPE, and pipefail makes that 141
  # the pipeline's status, so a line that is there would be reported missing on any log larger than
  # the pipe buffer. A whole run's latest.log is about 900 KB, well past it.
  if contents "$log" | grep 'Starting JEI GUI' > /dev/null; then
    started=true
  fi
  # The filename goes in front of every error line, because the line itself carries no record of
  # which file of the set it came from once the files are searched together.
  if matches="$(contents "$log" | grep -E "$ERROR_RE")"; then
    errors+="$(printf '%s\n' "$matches" | sed "s|^|$log: |")"$'\n'
  fi
done

if [ "$started" = false ]; then
  echo "==> ERROR: JEI never logged 'Starting JEI GUI' in $logs_csv"
  exit 1
fi

if [ -n "$errors" ]; then
  printf '%s' "$errors"
  echo "==> ERROR: JEI logged the errors above, in $logs_csv"
  exit 1
fi