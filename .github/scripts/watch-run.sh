#!/usr/bin/env bash
# Watch a GitHub Actions run and stop at the first failed job instead of the end of the run.
#
#   watch-run.sh <run-id> [OWNER/REPO]
#
# Polls `gh run view --json status,jobs` (WATCH_RUN_INTERVAL seconds apart, 15 by default). Exits 1
# at the first job that failed, after printing the last 80 lines of its failed step, and 0 once
# every job has finished without a failure. A skipped job is a pass. Only job names, conclusions and
# log lines are printed.
set -euo pipefail

RUN_ID="${1:?usage: watch-run.sh <run-id> [OWNER/REPO]}"
REPO="${2:-grug-lang/grug-for-minecraft}"
INTERVAL="${WATCH_RUN_INTERVAL:-15}"

while true; do
  JSON="$(gh run view "$RUN_ID" --repo "$REPO" --json status,jobs)"
  FAILED="$(jq -r '[.jobs[] | select(.conclusion == "failure" or .conclusion == "timed_out" or .conclusion == "cancelled")][0] | if . then "\(.databaseId)\t\(.name)\t\(.conclusion)" else empty end' <<<"$JSON")"
  if [ -n "$FAILED" ]; then
    IFS=$'\t' read -r JOB_ID JOB_NAME CONCLUSION <<<"$FAILED"
    echo "==> Job '$JOB_NAME' ($JOB_ID) ended with $CONCLUSION; last 80 lines of its failed step:"
    gh run view --job "$JOB_ID" --repo "$REPO" --log-failed 2>&1 | tail -n 80 || true
    exit 1
  fi
  STATUS="$(jq -r '.status' <<<"$JSON")"
  PENDING="$(jq '[.jobs[] | select(.status != "completed")] | length' <<<"$JSON")"
  if [ "$STATUS" = completed ] && [ "$PENDING" = 0 ]; then
    echo "==> Run $RUN_ID finished with every job green."
    exit 0
  fi
  echo "==> $(date +%T) run $RUN_ID: $PENDING job(s) not finished yet"
  sleep "$INTERVAL"
done
