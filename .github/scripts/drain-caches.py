#!/usr/bin/env python3
"""Delete the caches of a merged pull request, which no later run can restore (#825).

    drain-caches.py <pull-request> [--dry-run] [--api-base <url>] [--repo-slug <owner/repo>]

A cache entry is readable only from the ref that wrote it and from the default branch, so the
entries under a pull request's ``refs/pull/<n>/merge`` and under its head branch cannot be restored
once it merges: a merged pull request never runs again, its head branch is deleted on merge here, and
the push to ``main`` that carried it recomputes its keys under ``refs/heads/main``. Nothing else
removes them, so they sat in the store until the 10 GB cap evicted something a run still needed,
which happened twice before (#699). This deletes exactly those two refs of one merged pull request,
and nothing else: an unmerged pull request's entries are still its own (it can be reopened and run
again), ``main``'s are never named, and a head branch whose owner is not this repository is not in
this store's refs at all. A head branch that outlived its merge is drained with the rest: its work is
in ``main``, and a branch kept for later is new work that warms its own caches.

It reads the pull request itself rather than trusting its caller, so running it by hand for a pull
request that has not merged deletes nothing. ``--dry-run`` prints what it would delete.

Prints one line per ref with the entries and bytes it deleted, then a total, and exits 1 when a
deletion failed, so a drain that stopped working is a red run rather than a store filling up. An
entry the cap evicted between the listing and the deletion is reported as already gone, not failed.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

# A branch of one of these names is never drained, whatever names it. Neither can be a merged pull
# request's head here, so this only stops a mistake from emptying the default branch's caches.
PROTECTED_REFS = ("refs/heads/main", "refs/heads/master")
# The most entries one listing page holds. The script pages until a page comes back short, so an
# empty store, a store over the cap and a pull request with a full generation all work.
PER_PAGE = 100


def api(url: str, token: str, method: str = "GET"):
    """The JSON at url, authenticated with the workflow's token, or None for 204, or raises."""
    request = urllib.request.Request(
        url,
        method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with urllib.request.urlopen(request) as response:
        if response.status == 204:
            return None
        return json.load(response)


def pull_request(api_base: str, slug: str, number: int, token: str) -> dict:
    """The pull request GitHub holds, which carries whether it merged and what its branch is."""
    return api(f"{api_base}/repos/{slug}/pulls/{number}", token)


def refs_to_drain(pull: dict, number: int, slug: str) -> list[str]:
    """The refs of this pull request that no run can restore any more, or [] when it has not merged.

    The merge ref is this repository's whatever the head is, since the base repository runs the pull
    request's workflows and a cache is written to the repository whose run wrote it. The head branch
    is drained only when the pull request's own repository holds it: a fork's branch of the same name
    in this repository is another piece of work, and a deleted fork names no branch at all.
    """
    if not pull.get("merged_at"):
        return []
    refs = [f"refs/pull/{number}/merge"]
    head = pull.get("head") or {}
    head_repo = head.get("repo") or {}
    branch = head.get("ref") or ""
    base = (pull.get("base") or {}).get("ref") or ""
    if head_repo.get("full_name") != slug or not branch or branch == base:
        return refs
    ref = f"refs/heads/{branch}"
    if ref not in PROTECTED_REFS:
        refs.append(ref)
    return refs


def caches_of(api_base: str, slug: str, ref: str, token: str) -> list[dict]:
    """Every cache entry of one ref, paged, as GitHub lists it."""
    url = f"{api_base}/repos/{slug}/actions/caches"
    entries: list[dict] = []
    page = 1
    while True:
        params = urllib.parse.urlencode({"ref": ref, "per_page": PER_PAGE, "page": page})
        batch = api(f"{url}?{params}", token).get("actions_caches") or []
        entries.extend(batch)
        if len(batch) < PER_PAGE:
            return entries
        page += 1


def delete_cache(api_base: str, slug: str, cache_id: int, token: str) -> bool:
    """Delete one entry, False when it was already gone, and raise on any other refusal."""
    url = f"{api_base}/repos/{slug}/actions/caches/{cache_id}"
    try:
        api(url, token, method="DELETE")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return False
        raise
    return True


def megabytes(count: int) -> str:
    """A byte count as the audits report it, in millions of bytes with one decimal."""
    return f"{count / 1_000_000:.1f} MB"


def drain(api_base: str, slug: str, number: int, token: str, dry_run: bool) -> int:
    """Drain one merged pull request's refs, and return the exit code."""
    pull = pull_request(api_base, slug, number, token)
    refs = refs_to_drain(pull, number, slug)
    action = "would delete" if dry_run else "deleted"
    if not refs:
        print(
            f"drain-caches: #{number} has not merged, so its refs are still its own; deleted nothing",
            file=sys.stderr,
        )
        return 0
    print(f"drain-caches: #{number} merged, draining {', '.join(refs)}", file=sys.stderr)
    total_entries = total_bytes = gone = 0
    failures = 0
    for ref in refs:
        entries = caches_of(api_base, slug, ref, token)
        ref_entries = ref_bytes = 0
        for entry in entries:
            cache_id = entry["id"]
            if not dry_run:
                try:
                    if not delete_cache(api_base, slug, cache_id, token):
                        gone += 1
                        continue
                except Exception as e:  # noqa: BLE001 - one failed entry still drains the rest
                    failures += 1
                    print(
                        f"drain-caches: {ref}: {cache_id} ({entry['key']}) could not be deleted:"
                        f" {type(e).__name__}: {e}",
                        file=sys.stderr,
                    )
                    continue
            ref_entries += 1
            ref_bytes += entry["size_in_bytes"]
        total_entries += ref_entries
        total_bytes += ref_bytes
        print(f"drain-caches: {ref}: {ref_entries} entries, {megabytes(ref_bytes)} {action}")
    print(
        f"drain-caches: {total_entries} entries, {megabytes(total_bytes)} {action},"
        f" {gone} already gone"
    )
    if failures:
        print(f"drain-caches: {failures} entries could not be deleted", file=sys.stderr)
        return 1
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("number", type=int, help="the pull request's number")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print what would be deleted, and delete nothing",
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
            "drain-caches: deleted nothing: GITHUB_REPOSITORY is not set, which names the repository",
            file=sys.stderr,
        )
        return 1
    try:
        return drain(args.api_base, args.repo_slug, args.number, token, args.dry_run)
    except Exception as e:  # noqa: BLE001 - a drain that cannot read the API has not finished
        print(
            f"drain-caches: stopped: the GitHub API could not be read: {type(e).__name__}: {e};"
            f" whatever was deleted before this point stays deleted",
            file=sys.stderr,
        )
        return 1


if __name__ == "__main__":
    sys.exit(main())
