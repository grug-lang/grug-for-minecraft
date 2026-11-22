#!/usr/bin/env python3
"""Which server-exec-<loader> artifacts the coverage job downloads from an earlier run (#1602).

    base_server_exec_artifacts.py

reads the GITHUB_TOKEN, GITHUB_REPOSITORY and GITHUB_API_URL environment and prints one
`<artifact name> <download url>` line per live artifact of the newest run that has any, and says on
stderr which run and how old it is. Only the dispatch-only multiplayer job uploads server-exec, so
the run is not the pull request's base run (a push to main) but the newest dispatch or scheduled
run of build.yml on main within the 7 day retention, among the 10 newest. No such run, or any
error, answers no lines and exit 0: the carry-over is a source of exec data for a promoted class,
and without it the gate counts what the run's own legs recorded, as before.
"""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.request
from datetime import datetime, timedelta, timezone

RETENTION = timedelta(days=7)
EVENTS = ("workflow_dispatch", "schedule")
MAX_RUNS = 10
MAX_PAGES = 5

NAME = re.compile(r"^server-exec-(.+)$")


def pick(listing: dict) -> tuple[list[tuple[str, str]], str]:
    """The live (name, url) pairs of the listing's server-exec artifacts, and a sentence on why."""
    named = [a for a in listing.get("artifacts", []) if NAME.match(a.get("name", ""))]
    live = [a for a in named if not a.get("expired", False)]
    if not named:
        return [], "the base run uploaded no server-exec artifact: it ran no dedicated leg"
    if not live:
        return [], (
            f"the base run's {len(named)} server-exec artifact(s) expired (retention is 7 days): "
            "nothing is carried over"
        )
    pairs = sorted((a["name"], a["archive_download_url"]) for a in live)
    return pairs, f"the base run has {len(pairs)} live server-exec artifact(s)"


def parse_time(text: str) -> datetime:
    return datetime.fromisoformat(text.replace("Z", "+00:00"))


def candidate_runs(listing: dict, now: datetime) -> list[dict]:
    """The newest dispatch or scheduled completed runs within retention, newest first, at most 10."""
    runs = [
        run
        for run in listing.get("workflow_runs", [])
        if run.get("event") in EVENTS
        and run.get("status") == "completed"
        and now - parse_time(run["created_at"]) <= RETENTION
    ]
    runs.sort(key=lambda run: run["created_at"], reverse=True)
    return runs[:MAX_RUNS]


def choose_run(runs: list[dict], fetch) -> tuple[dict | None, list[tuple[str, str]]]:
    """The first run, newest first, whose artifacts (fetch(run id) -> listing) hold a live one."""
    for run in runs:
        pairs, _ = pick(fetch(run["id"]))
        if pairs:
            return run, pairs
    return None, []


def get_json(url: str, token: str):
    request = urllib.request.Request(
        url,
        headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def main() -> int:
    api = os.environ.get("GITHUB_API_URL", "https://api.github.com")
    slug = os.environ.get("GITHUB_REPOSITORY", "")
    token = os.environ.get("GITHUB_TOKEN", "")
    now = datetime.now(timezone.utc)

    def fetch(run_id):
        artifacts: list[dict] = []
        for page in range(1, MAX_PAGES + 1):
            url = f"{api}/repos/{slug}/actions/runs/{run_id}/artifacts?per_page=100&page={page}"
            batch = get_json(url, token).get("artifacts", [])
            artifacts += batch
            if len(batch) < 100:
                break
        return {"artifacts": artifacts}

    try:
        url = (
            f"{api}/repos/{slug}/actions/workflows/build.yml/runs"
            "?branch=main&status=completed&per_page=100"
        )
        run, pairs = choose_run(candidate_runs(get_json(url, token), now), fetch)
    except Exception as error:  # a lookup that fails carries nothing and never fails the job
        print(
            f"no dedicated-server exec data carried over: lookup failed ({error})", file=sys.stderr
        )
        return 0
    if run is None:
        print(
            "no dedicated-server exec data carried over: none of the 10 newest dispatch or "
            "scheduled runs of build.yml on main within 7 days has a live server-exec artifact",
            file=sys.stderr,
        )
        return 0
    age = now - parse_time(run["created_at"])
    print(
        f"carrying over the server-exec artifacts of run {run['id']} "
        f"({run['event']}, {age.total_seconds() / 3600:.1f} h old)",
        file=sys.stderr,
    )
    for name, link in pairs:
        print(name, link)
    return 0


if __name__ == "__main__":
    sys.exit(main())
