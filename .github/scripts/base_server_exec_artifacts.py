#!/usr/bin/env python3
"""Which server-exec-<loader> artifacts the coverage job downloads from an earlier run (#1602).

    base_server_exec_artifacts.py

reads the GITHUB_TOKEN, GITHUB_REPOSITORY and GITHUB_API_URL environment and prints one
`<artifact name> <download url>` line per live artifact of the newest run that has any, and says on
stderr which run and how old it is. A leg runs on a dispatch, on a schedule and, for each loader whose
code the diff touches, on a push to main and a pull request (#1601), so the artifacts of one loader
come from the newest run of build.yml on main (a dispatch, a schedule or a push) within the 7 day
retention, among the 20 newest, that uploaded one: a loader's class bytes only change in a run that
touched it, and that run's leg recorded them, so the newest artifact of a loader matches the bytes
of main's head until a later push changes the loader and records its own. No such run, or any
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
from pathlib import Path

RETENTION = timedelta(days=7)
EVENTS = ("workflow_dispatch", "schedule", "push")
MAX_RUNS = 20
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


def choose_runs(
    runs: list[dict], fetch, wanted: set[str] | None = None
) -> list[tuple[dict, list[tuple[str, str]]]]:
    """Per artifact name, the newest run (fetch(run id) -> listing) holding a live one.

    The result is one (run, pairs) entry per run that supplies at least one name, newest first, so
    the log can name each run. ``wanted`` names the artifacts to look for: the walk stops once every
    one is found, and None walks every run.
    """
    found: set[str] = set()
    chosen: list[tuple[dict, list[tuple[str, str]]]] = []
    for run in runs:
        pairs, _ = pick(fetch(run["id"]))
        fresh = [(name, url) for name, url in pairs if name not in found]
        if fresh:
            chosen.append((run, fresh))
            found.update(name for name, _ in fresh)
        if wanted is not None and wanted <= found:
            break
    return chosen


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
        loader_dirs = Path("loaders")
        wanted = (
            {f"server-exec-{d.name}" for d in loader_dirs.iterdir() if d.is_dir()}
            if loader_dirs.is_dir()
            else None
        )
        chosen = choose_runs(candidate_runs(get_json(url, token), now), fetch, wanted)
    except Exception as error:  # a lookup that fails carries nothing and never fails the job
        print(
            f"no dedicated-server exec data carried over: lookup failed ({error})", file=sys.stderr
        )
        return 0
    if not chosen:
        print(
            "no dedicated-server exec data carried over: none of the 20 newest dispatch, scheduled or "
            "push runs of build.yml on main within 7 days has a live server-exec artifact",
            file=sys.stderr,
        )
        return 0
    for run, pairs in chosen:
        age = now - parse_time(run["created_at"])
        print(
            f"carrying over {', '.join(name for name, _ in pairs)} of run {run['id']} "
            f"({run['event']}, {age.total_seconds() / 3600:.1f} h old)",
            file=sys.stderr,
        )
        for name, link in pairs:
            print(name, link)
    return 0


if __name__ == "__main__":
    sys.exit(main())
