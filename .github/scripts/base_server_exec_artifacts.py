#!/usr/bin/env python3
"""Which server-exec-<loader> and server-layer-<loader> artifacts the coverage job downloads from an earlier run (#1602).

    base_server_exec_artifacts.py [--head-shas]

reads the GITHUB_TOKEN, GITHUB_REPOSITORY and GITHUB_API_URL environment and prints one
`<artifact name> <download url>` line per live artifact of the newest run that has any, and says on
stderr which run and how old it is. A leg runs on a dispatch, on a schedule and, for each loader whose
code the diff touches, on a push to main and a pull request (#1601), so the artifacts of one loader
come from the newest run of build.yml on main (a dispatch, a schedule or a push) within the 7 day
retention, among the 50 newest, whose leg for that loader concluded success and uploaded one: a loader's class bytes only change in a run that
touched it, and that run's leg recorded them, so the newest artifact of a loader matches the bytes
of main's head until a later push changes the loader and records its own. No such run, or any
error, answers no lines and exit 0: the carry-over is a source of exec data for a promoted class,
and without it the gate counts what the run's own legs recorded, as before.

``--head-shas`` prints one `<loader> <head commit>` line instead, for the run whose artifact of that
loader is carried over: the discover job diffs that commit against the run's head to decide whether
the recording still matches the loader's class bytes (#1739). A loader with no live artifact prints
nothing, which is the missing case that runs its dedicated leg.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

RETENTION = timedelta(days=7)
EVENTS = ("workflow_dispatch", "schedule", "push")
MAX_RUNS = 50
MAX_PAGES = 5

NAME = re.compile(r"^server-(?:exec|layer)-(.+)$")


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


LEG = re.compile(r"^(.+) on a dedicated server$")


def head_shas(chosen: list[tuple[dict, list[tuple[str, str]]]]) -> dict[str, str]:
    """Per loader, the head commit of the run whose live server-exec artifact is carried over.

    ``server-layer-<loader>`` names the jar the same leg ran, not a recording of its own, so only
    the exec artifacts are read. An empty commit is returned as such rather than left out: the run
    named no commit, which the caller must treat like a missing artifact.
    """
    found: dict[str, str] = {}
    for run, pairs in chosen:
        for name, _ in pairs:
            match = NAME.match(name)
            if not match or not name.startswith("server-exec-"):
                continue
            found.setdefault(match.group(1), run.get("head_sha", ""))
    return found


def succeeded_loaders(jobs: dict) -> set[str]:
    """The loaders whose dedicated-server leg concluded success in a run's job listing."""
    found = set()
    for job in jobs.get("jobs", []):
        match = LEG.match(job.get("name", ""))
        if match and job.get("conclusion") == "success":
            found.add(match.group(1))
    return found


def choose_runs(
    runs: list[dict], fetch, wanted: set[str] | None = None, fetch_jobs=None
) -> list[tuple[dict, list[tuple[str, str]]]]:
    """Per artifact name, the newest run (fetch(run id) -> listing) holding a live one.

    The result is one (run, pairs) entry per run that supplies at least one name, newest first, so
    the log can name each run. ``wanted`` names the artifacts to look for: the walk stops once every
    one is found, and None walks every run. With ``fetch_jobs`` (run id -> job listing), an artifact
    counts only when that loader's leg concluded success in the run, so a failed or partial leg never
    shadows a good artifact (#1601); a run is asked for its jobs only when it holds a live artifact,
    so a run that ran no multiplayer job (a docs-only push) costs one request.
    """
    found: set[str] = set()
    chosen: list[tuple[dict, list[tuple[str, str]]]] = []
    for run in runs:
        pairs, _ = pick(fetch(run["id"]))
        if pairs and fetch_jobs is not None:
            good = succeeded_loaders(fetch_jobs(run["id"]))
            pairs = [(n, u) for n, u in pairs if NAME.match(n).group(1) in good]
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


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument(
        "--head-shas",
        action="store_true",
        help="print the head commit of each loader's artifact run instead of the artifact urls",
    )
    args = parser.parse_args(argv)
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

    def fetch_jobs(run_id):
        url = f"{api}/repos/{slug}/actions/runs/{run_id}/jobs?per_page=100&filter=latest"
        return get_json(url, token)

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
        chosen = choose_runs(candidate_runs(get_json(url, token), now), fetch, wanted, fetch_jobs)
    except Exception as error:  # a lookup that fails carries nothing and never fails the job
        print(
            f"no dedicated-server exec data carried over: lookup failed ({error})", file=sys.stderr
        )
        return 0
    if not chosen:
        print(
            "no dedicated-server exec data carried over: none of the 50 newest dispatch, scheduled or "
            "push runs of build.yml on main within 7 days has a live server-exec artifact of a leg that succeeded",
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
    if args.head_shas:
        for loader, sha in sorted(head_shas(chosen).items()):
            print(loader, sha)
        return 0
    for run, pairs in chosen:
        for name, link in pairs:
            print(name, link)
    return 0


if __name__ == "__main__":
    sys.exit(main())
