#!/usr/bin/env bash
# Watch a GitHub Actions run and stop at the first failed job instead of the end of the run.
#
#   watch-run.sh <run-id> [OWNER/REPO]
#
# Polls `gh run view --json status,conclusion,jobs` (WATCH_RUN_INTERVAL seconds apart, 15 by default).
# Exits 1 at the first job that failed, after printing the 80 log lines before its first error, and 0
# once the run has finished with a success or skipped conclusion. A skipped job is a pass. Only job names, conclusions and
# log lines are printed.
set -euo pipefail

RUN_ID="${1:?usage: watch-run.sh <run-id> [OWNER/REPO]}"
REPO="${2:-grug-lang/grug-for-minecraft}"
INTERVAL="${WATCH_RUN_INTERVAL:-15}"

while true; do
  JSON="$(gh run view "$RUN_ID" --repo "$REPO" --json status,conclusion,jobs)"
  FAILED="$(jq -r '[.jobs[] | select(.conclusion == "failure" or .conclusion == "timed_out" or .conclusion == "cancelled")][0] | if . then "\(.databaseId)\t\(.name)\t\(.conclusion)" else empty end' <<<"$JSON")"
  if [ -n "$FAILED" ]; then
    IFS=$'\t' read -r JOB_ID JOB_NAME CONCLUSION <<<"$FAILED"
    echo "==> Job '$JOB_NAME' ($JOB_ID) ended with $CONCLUSION; the 80 log lines before its first error:"
    # `gh run view --log-failed` refuses while the run is in progress, which is when this matters, so
    # read the job's log through the API, which works once the job itself has finished.
    LOG="$(gh api "repos/$REPO/actions/jobs/$JOB_ID/logs" --allow-escape-sequences 2>&1 || true)"
    LINE="$(grep -n -m1 '##\[error\]' <<<"$LOG" | cut -d: -f1 || true)"
    if [ -n "$LINE" ]; then head -n "$LINE" <<<"$LOG" | tail -n 80; else tail -n 80 <<<"$LOG"; fi
    exit 1
  fi
  STATUS="$(jq -r '.status' <<<"$JSON")"
  PENDING="$(jq '[.jobs[] | select(.status != "completed")] | length' <<<"$JSON")"
  if [ "$STATUS" = completed ] && [ "$PENDING" = 0 ]; then
    RUN_CONCLUSION="$(jq -r '.conclusion // ""' <<<"$JSON")"
    if [ "$RUN_CONCLUSION" = success ] || [ "$RUN_CONCLUSION" = skipped ]; then
      echo "==> Run $RUN_ID finished with every job green."
      exit 0
    fi
    echo "==> Run $RUN_ID finished with conclusion '$RUN_CONCLUSION' and no failed job."
    exit 1
  fi
  echo "==> $(date +%T) run $RUN_ID: $PENDING job(s) not finished yet"
  sleep "$INTERVAL"
done
