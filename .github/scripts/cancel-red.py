#!/usr/bin/env python3
"""Cancel a proof dispatch of build.yml as soon as one of its jobs has failed (#1343).

    cancel-red.py <run-id> --own-job <name> [--repo-slug <owner/repo>] [--interval <seconds>]
                  [--exempt <regex>]...

A dispatch with `base` or `mods` is a proof: one red leg already decides the answer, and the legs
that go green afterwards cost most of a red dispatch's runner minutes. The workflow starts this in
a job of its own for such a dispatch only, never for a no-input dispatch, the four-hourly check,
the release, a push or a pull request, which want every job's result.

It polls the run's jobs. The first job whose conclusion is `failure` and whose name matches no
`--exempt` regex (a known flake) cancels the run through the API; it then exits 0. When every job
other than its own has completed without such a failure it exits 0 as well. A job list that cannot
be read is retried and never cancels anything.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time


def failing_job(jobs: list[dict], own_job: str, exempt: list[str]) -> dict | None:
    """The earliest-completed failed job that is neither this watcher nor exempt, or None."""
    patterns = [re.compile(p) for p in exempt]
    failed = [
        j
        for j in jobs
        if j.get("conclusion") == "failure"
        and j.get("name") != own_job
        and not any(p.search(j.get("name", "")) for p in patterns)
    ]
    if not failed:
        return None
    return min(failed, key=lambda j: j.get("completed_at") or "9999")


def all_done(jobs: list[dict], own_job: str) -> bool:
    """Whether every job but the watcher has completed; a run still creating jobs is not done."""
    others = [j for j in jobs if j.get("name") != own_job]
    return bool(others) and all(j.get("status") == "completed" for j in others)


def fetch_jobs(slug: str, run_id: str) -> list[dict]:
    out = subprocess.run(
        [
            "gh",
            "api",
            "--paginate",
            "--slurp",
            f"repos/{slug}/actions/runs/{run_id}/jobs?per_page=100",
        ],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return [j for page in json.loads(out) for j in page["jobs"]]


def cancel(slug: str, run_id: str) -> None:
    subprocess.run(
        ["gh", "api", "-X", "POST", f"repos/{slug}/actions/runs/{run_id}/cancel"],
        check=True,
        capture_output=True,
        text=True,
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("run_id")
    ap.add_argument("--own-job", required=True)
    ap.add_argument("--repo-slug", default="grug-lang/grug-for-minecraft")
    ap.add_argument("--interval", type=float, default=30)
    ap.add_argument("--exempt", action="append", default=[])
    args = ap.parse_args()
    while True:
        try:
            jobs = fetch_jobs(args.repo_slug, args.run_id)
        except Exception as e:  # noqa: BLE001 - an unreadable list must not cancel; try again
            print(f"cancel-red: could not read the jobs ({type(e).__name__}), retrying")
            time.sleep(args.interval)
            continue
        bad = failing_job(jobs, args.own_job, args.exempt)
        if bad:
            print(
                f"cancel-red: '{bad['name']}' failed, so this proof dispatch is cancelled: {bad.get('html_url')}"
            )
            cancel(args.repo_slug, args.run_id)
            return 0
        if all_done(jobs, args.own_job):
            print("cancel-red: every job finished and none failed")
            return 0
        time.sleep(args.interval)


if __name__ == "__main__":
    sys.exit(main())
