#!/usr/bin/env python3
"""Dispatch main's full Build run when the four-hourly check is due (#1089).

    main-dispatch.py [--dry-run] [--interval-minutes <n>] [--api-base <url>]
                     [--repo-slug <owner/repo>]

Only a dispatch of ``build.yml`` runs every job, and which jobs a push to ``main`` skips can change
with the workflow, so AGENTS.md asks for a full dispatch of ``main`` at least every four hours. A
day without a live session to dispatch it by hand let the newest one reach 25 hours (#1089), so
this runs from ``.github/workflows/main-dispatch.yml`` on a schedule, and the orchestrator only
dispatches by hand at a task boundary when a due one has not appeared.

The rule, in ``should_dispatch``: a dispatch is due when ``main``'s newest ``workflow_dispatch`` run
of ``build.yml`` is at least the interval old, whatever else is running. A due tick dispatches even
while other ``build.yml`` runs are live, and its jobs queue behind them for the 20 shared job slots,
because waiting for an idle pool is what starved the check: a proof or a pull request was in flight
at every tick and the full check went 106 hours without running (#1463), so the Windows adapter
broke and nothing saw it until a proof dispatch ran the Windows jobs (#1460). The Sunday release's
dispatch is a ``workflow_dispatch`` on ``main`` like any other, so a recent one holds the check off
too. The runs API does not expose a dispatch's inputs, so one sent with ``-f mods=`` or ``-f base=``
counts as a full one even though it ran fewer jobs, and a partial dispatch can hold a full check
off; that is a known limit. The newest dispatch is read once more just before the dispatch is sent,
so a dispatch another tick created in between cannot make this check add a second one.

Prints one line saying why it did or did not dispatch. A run list that cannot be read fails the
run rather than dispatching blindly, and a refused dispatch fails it too.
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import sys
import urllib.parse
import urllib.request

# The interval AGENTS.md asks for between full dispatches of main.
INTERVAL = datetime.timedelta(hours=4)
# The most runs one listing page holds. The newest dispatch is always on the first page, which is
# the only one read, whatever the repository's run history.
PER_PAGE = 100


def api(url: str, token: str, method: str = "GET", body: dict | None = None):
    """The JSON at url, authenticated with the workflow's token, or None for 204, or raises."""
    data = None
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    with urllib.request.urlopen(request) as response:
        if response.status == 204:
            return None
        return json.load(response)


def workflow_runs(api_base: str, slug: str, token: str, params: dict) -> list[dict]:
    """One page of build.yml's runs matching params, in the order GitHub returns them."""
    query = urllib.parse.urlencode(params)
    url = f"{api_base}/repos/{slug}/actions/workflows/build.yml/runs?{query}"
    listing = api(url, token)
    if not isinstance(listing, dict) or not isinstance(listing.get("workflow_runs"), list):
        raise ValueError("the run list has no workflow_runs")
    return listing["workflow_runs"]


def when(value: str) -> datetime.datetime:
    """A GitHub timestamp, which is UTC with a Z, as an aware datetime."""
    return datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))


def newest(runs: list[dict]) -> dict | None:
    """The run created last, whatever order the listing came in."""
    return max(runs, key=lambda run: when(run["created_at"]), default=None)


def duration(seconds: float) -> str:
    """A span as the audits report it: hours and minutes, or minutes alone."""
    seconds = max(int(seconds), 0)
    hours, rest = divmod(seconds, 3600)
    minutes = rest // 60
    if hours:
        return f"{hours} h {minutes} min"
    return f"{minutes} min"


def should_dispatch(
    now: datetime.datetime,
    dispatches: list[dict],
    interval: datetime.timedelta = INTERVAL,
) -> tuple[bool, str]:
    """Whether a full dispatch of main is due, and the sentence that says why.

    A dispatch is due when the newest dispatch is at least the interval old, whatever else is
    running: a due tick's jobs queue behind the runs that are live rather than the check waiting for
    an idle pool, which starved it for 106 hours and hid a broken Windows adapter (#1463, #1460).
    The newest dispatch is picked by its creation time, not by its position in the listing, so a
    reordered or filtered page still gives the same answer. A dispatch exactly one interval old is
    due.
    """
    latest = newest(dispatches)
    age = None if latest is None else (now - when(latest["created_at"])).total_seconds()
    if latest is None:
        return True, "main has no build.yml dispatch yet, so the four-hourly check is due"
    if age >= interval.total_seconds():
        return True, (
            f"main's newest build.yml dispatch is {duration(age)} old, at least the"
            f" {duration(interval.total_seconds())} interval, so the four-hourly check is due and"
            f" its jobs queue behind any run in flight"
        )
    return False, (
        f"main's newest build.yml dispatch is {duration(age)} old, younger than the"
        f" {duration(interval.total_seconds())} interval, so the next check waits"
    )


def dispatch(api_base: str, slug: str, token: str) -> None:
    """Ask GitHub to run build.yml on main's head, which is what a full check is."""
    url = f"{api_base}/repos/{slug}/actions/workflows/build.yml/dispatches"
    api(url, token, method="POST", body={"ref": "main"})


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print the decision and dispatch nothing",
    )
    parser.add_argument(
        "--interval-minutes",
        type=int,
        default=None,
        help="the interval in minutes (default: 240, the four-hourly rule)",
    )
    parser.add_argument(
        "--api-base",
        default=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        help="the GitHub API base url (default: the GITHUB_API_URL environment, then the real api)",
    )
    parser.add_argument(
        "--repo-slug",
        default=os.environ.get("GITHUB_REPOSITORY", ""),
        help="owner/repo, from the GITHUB_REPOSITORY environment (default: the environment)",
    )
    args = parser.parse_args(argv)
    token = os.environ.get("GITHUB_TOKEN", "")
    if not args.repo_slug:
        print(
            "main-dispatch: dispatched nothing: GITHUB_REPOSITORY is not set, which names the"
            " repository",
            file=sys.stderr,
        )
        return 1
    if args.interval_minutes is not None and args.interval_minutes < 0:
        parser.error("--interval-minutes cannot be negative")
    interval = (
        INTERVAL
        if args.interval_minutes is None
        else datetime.timedelta(minutes=args.interval_minutes)
    )
    try:
        dispatches = workflow_runs(
            args.api_base,
            args.repo_slug,
            token,
            {"event": "workflow_dispatch", "branch": "main", "per_page": PER_PAGE},
        )
        due, reason = should_dispatch(
            datetime.datetime.now(datetime.timezone.utc),
            dispatches,
            interval,
        )
    except Exception as e:  # noqa: BLE001 - an unreadable run list must not dispatch blindly
        print(
            f"main-dispatch: stopped: could not read the build.yml run list:"
            f" {type(e).__name__}: {e}; dispatched nothing",
            file=sys.stderr,
        )
        return 1
    print(f"main-dispatch: {reason}")
    if not due:
        return 0
    if args.dry_run:
        print("main-dispatch: dry run, so this check dispatched nothing")
        return 0
    # Another tick, or the orchestrator's own dispatch, can create the dispatch this check measured
    # between its read and this line, and an age that is no longer the newest would double it, so
    # the newest is read once more before dispatching.
    try:
        latest_now = newest(
            workflow_runs(
                args.api_base,
                args.repo_slug,
                token,
                {"event": "workflow_dispatch", "branch": "main", "per_page": PER_PAGE},
            )
        )
    except Exception as e:  # noqa: BLE001 - an unreadable run list must not dispatch blindly
        print(
            f"main-dispatch: stopped: could not re-read the newest dispatch: {type(e).__name__}:"
            f" {e}; dispatched nothing",
            file=sys.stderr,
        )
        return 1
    if (newest(dispatches) or {}).get("id") != (latest_now or {}).get("id"):
        print(
            "main-dispatch: dispatched nothing: a build.yml dispatch of main appeared while this"
            " check read the run lists, so the age it measured is not the newest one"
        )
        return 0
    try:
        dispatch(args.api_base, args.repo_slug, token)
    except Exception as e:  # noqa: BLE001 - a refused dispatch has not started the check
        print(
            f"main-dispatch: stopped: the dispatch was refused: {type(e).__name__}: {e}",
            file=sys.stderr,
        )
        return 1
    print("main-dispatch: dispatched build.yml on main")
    return 0


if __name__ == "__main__":
    sys.exit(main())
