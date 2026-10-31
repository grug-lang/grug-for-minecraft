#!/usr/bin/env python3
"""The run a pull request may carry over its base commit's coverage from, or nothing (#786).

    base_coverage_map_run.py <base-sha> [--repo <dir>] [--api-base <url>] [--repo-slug <owner/repo>]

prints the id of the successful run of the base commit on main that uploaded a coverage-map
artifact, so the coverage job can download it, and nothing when no such run exists, which is the
same fallback as an error: a base commit without a recorded map has nothing to carry over, so the
pull request runs every coverage test rather than carry one over. A run on main whose head is the
base commit is a run of the base commit's own code: a push to main and a dispatch check the head
out, and a pull request checks out its head merged into its base, which is the head's own code
when its base is behind it; the coverage job then applies the map against the pull request's
own core classes and fails the run when a class changed under it, which is the direction that
runs more. A run that failed, or whose map's retention expired, has no artifact to read. The
lookup is a read of the GitHub API, and a read that cannot be made answers nothing, which is the
same fallback as a base that is not there to read.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request

# The newest-first runs of main the lookup may page through, and why: the base commit is at most
# a few runs behind main's head, and a base older than that has no map anyway, since the map's
# retention is shorter than the gap it would have to span.
MAX_PAGES = 5
MAP_ARTIFACT = "coverage-map"


def get_json(url: str, token: str):
    """The JSON at url, authenticated with the workflow's token, or raises."""
    request = urllib.request.Request(
        url,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
        },
    )
    with urllib.request.urlopen(request) as response:
        return json.load(response)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("base", help="the commit the pull request is compared with")
    parser.add_argument("--repo", default=".", help="the git repository (default: the current one)")
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

    try:
        # The base commit's committer date is the earliest its push to main could have run, and
        # it keeps the lookup from paging through main's whole history.
        created = subprocess.run(
            ["git", "-C", args.repo, "show", "-s", "--format=%cI", args.base],
            capture_output=True,
            check=True,
            text=True,
        ).stdout.strip()
    except (OSError, subprocess.SubprocessError) as e:
        print(
            f"base-coverage-map-run: carrying over no coverage test: the base commit"
            f" {args.base} could not be read: {e}",
            file=sys.stderr,
        )
        return 0
    if not args.repo_slug:
        print(
            "base-coverage-map-run: carrying over no coverage test: GITHUB_REPOSITORY is not"
            " set, which names the repository",
            file=sys.stderr,
        )
        return 0

    runs_url = f"{args.api_base}/repos/{args.repo_slug}/actions/runs"
    try:
        found = None
        for page in range(1, MAX_PAGES + 1):
            # No event filter: a push to main and a dispatch check the head out as is, and a
            # pull request whose base is behind its head checks out the head's own code, so any
            # kind of run whose head is the base commit is a run of the base commit's code.
            params = {
                "branch": "main",
                "created": created,
                "per_page": "100",
                "page": str(page),
            }
            for run in get_json(runs_url + "?" + urllib.parse.urlencode(params), token)[
                "workflow_runs"
            ]:
                if run["head_sha"] == args.base and run["conclusion"] == "success":
                    found = run
                    break
            if found is not None:
                break
        if found is None:
            print(
                f"base-coverage-map-run: carrying over no coverage test: no successful run of"
                f" the base commit {args.base} on main was found",
                file=sys.stderr,
            )
            return 0
        artifacts_url = f"{runs_url}/{found['id']}/artifacts"
        params = {"per_page": "100"}
        artifacts = get_json(artifacts_url + "?" + urllib.parse.urlencode(params), token)[
            "artifacts"
        ]
        if not any(artifact["name"] == MAP_ARTIFACT for artifact in artifacts):
            print(
                f"base-coverage-map-run: carrying over no coverage test: the run of the base"
                f" commit {found['id']} uploaded no {MAP_ARTIFACT} artifact, which a failed"
                f" run or an expired retention leaves behind",
                file=sys.stderr,
            )
            return 0
        print(found["id"])
        return 0
    except Exception as e:  # noqa: BLE001 - whatever went wrong, the answer is to run everything
        print(
            f"base-coverage-map-run: carrying over no coverage test: the GitHub API could not"
            f" be read: {type(e).__name__}: {e}",
            file=sys.stderr,
        )
        return 0


if __name__ == "__main__":
    sys.exit(main())
