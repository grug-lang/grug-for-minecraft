#!/usr/bin/env python3
"""Delete the cache entries whose ref can never run again, on a schedule (#974).

    sweep-caches.py [--dry-run] [--api-base <url>] [--repo-slug <owner/repo>]

drain-caches.py (#825) frees a merged pull request's refs when it merges. Three kinds of dead entry
still reach nobody: a pull request closed without merging and never reopened, a branch deleted
without a pull request ever merging, and whatever a drain missed. A cache entry is readable only
from the ref that wrote it and from the default branch, so these sit in the 10 GB store until its
least-recently-used eviction throws out something a run still needs (#699).

The rule, in ``entries_to_delete``:

- ``refs/pull/<n>/merge`` goes unless pull request n is open.
- ``refs/heads/<b>`` goes unless branch b exists; ``refs/heads/main`` and ``master`` never go.
- Any other ref (a tag, ``refs/pull/<n>/head``) is left alone: the sweep names what it understands.

The cache listing is read first and the open pull requests and branches after it, so a pull request
or branch created in between has no entry in the listing and cannot be swept. ``--dry-run`` prints
what would be deleted. Exits 1 when a listing or a deletion failed.
"""

from __future__ import annotations

import argparse
import importlib.util
import os
import re
import sys
import urllib.parse
from pathlib import Path

# The drain script owns the API plumbing; this loads it by path because its name has a dash.
_SPEC = importlib.util.spec_from_file_location(
    "drain_caches", Path(__file__).resolve().parent / "drain-caches.py"
)
drain_caches = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(drain_caches)

PER_PAGE = drain_caches.PER_PAGE
PULL_MERGE_REF = re.compile(r"refs/pull/(\d+)/merge")
HEAD_PREFIX = "refs/heads/"


def entries_to_delete(entries: list[dict], open_pulls: set[int], branches: set[str]) -> list[dict]:
    """The entries whose ref is dead: a pull request that is not open, or a branch that is gone."""
    doomed = []
    for entry in entries:
        ref = entry.get("ref") or ""
        merge = PULL_MERGE_REF.fullmatch(ref)
        if merge:
            if int(merge.group(1)) not in open_pulls:
                doomed.append(entry)
        elif ref.startswith(HEAD_PREFIX):
            if ref not in drain_caches.PROTECTED_REFS and ref[len(HEAD_PREFIX) :] not in branches:
                doomed.append(entry)
    return doomed


def paged(url: str, token: str, key: str | None, params: dict) -> list:
    """Every item of a paged listing; key names the list inside an object, None for a bare list."""
    items: list = []
    page = 1
    while True:
        query = urllib.parse.urlencode({**params, "per_page": PER_PAGE, "page": page})
        body = drain_caches.api(f"{url}?{query}", token)
        batch = (body.get(key) if key else body) or []
        items.extend(batch)
        if len(batch) < PER_PAGE:
            return items
        page += 1


def sweep(api_base: str, slug: str, token: str, dry_run: bool) -> int:
    """List, decide, delete, and return the exit code."""
    base = f"{api_base}/repos/{slug}"
    # Caches first: anything created after this listing is not in it, so it cannot be swept.
    entries = paged(f"{base}/actions/caches", token, "actions_caches", {})
    open_pulls = {p["number"] for p in paged(f"{base}/pulls", token, None, {"state": "open"})}
    branches = {b["name"] for b in paged(f"{base}/branches", token, None, {})}
    doomed = entries_to_delete(entries, open_pulls, branches)
    action = "would delete" if dry_run else "deleted"
    print(
        f"sweep-caches: {len(entries)} entries, {len(open_pulls)} open pull requests,"
        f" {len(branches)} branches, {len(doomed)} dead"
    )
    per_ref: dict[str, list[int]] = {}
    gone = failures = 0
    for entry in doomed:
        if not dry_run:
            try:
                if not drain_caches.delete_cache(api_base, slug, entry["id"], token):
                    gone += 1
                    continue
            except Exception as e:  # noqa: BLE001 - one failed entry still sweeps the rest
                failures += 1
                print(
                    f"sweep-caches: {entry['ref']}: {entry['id']} ({entry['key']}) could not be"
                    f" deleted: {type(e).__name__}: {e}",
                    file=sys.stderr,
                )
                continue
        stats = per_ref.setdefault(entry["ref"], [0, 0])
        stats[0] += 1
        stats[1] += entry["size_in_bytes"]
    for ref, (count, size) in sorted(per_ref.items()):
        print(f"sweep-caches: {ref}: {count} entries, {drain_caches.megabytes(size)} {action}")
    total = sum(s[1] for s in per_ref.values())
    print(
        f"sweep-caches: {sum(s[0] for s in per_ref.values())} entries,"
        f" {drain_caches.megabytes(total)} {action}, {gone} already gone"
    )
    return 1 if failures else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("--dry-run", action="store_true", help="delete nothing, print the plan")
    parser.add_argument(
        "--api-base",
        default=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        help="the GitHub API base url (default: the GITHUB_API_URL environment, then the real api)",
    )
    parser.add_argument(
        "--repo-slug",
        default=os.environ.get("GITHUB_REPOSITORY", ""),
        help="owner/repo (default: the GITHUB_REPOSITORY environment)",
    )
    args = parser.parse_args(argv)
    if not args.repo_slug:
        print("sweep-caches: deleted nothing: GITHUB_REPOSITORY is not set", file=sys.stderr)
        return 1
    try:
        return sweep(
            args.api_base, args.repo_slug, os.environ.get("GITHUB_TOKEN", ""), args.dry_run
        )
    except Exception as e:  # noqa: BLE001 - a sweep that cannot read the API has not finished
        print(f"sweep-caches: stopped: {type(e).__name__}: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
