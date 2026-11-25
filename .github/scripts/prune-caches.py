#!/usr/bin/env python3
"""Delete the older cache generations a ref still holds, on a schedule (#1805).

    prune-caches.py [--dry-run] [--keep <n>] [--api-base <url>] [--repo-slug <owner/repo>]

A cache key that carries a hash of its inputs writes a new entry beside the old one whenever those
inputs change, and nothing ever deletes the old one. The sweep (#974) leaves them because their ref
is alive, so `main` held 119 entries in 53 key families on 2026-11-25, about 2.5 GB of them
generations no run can restore: `restore-keys` matches a family and the cache service hands back
the newest entry of it, so an older generation is dead weight the moment a newer one exists.

The measurement behind the rule is in #1805: over two days of `Build` runs, 31 jobs and 147 cache
restores, every restore was an exact hit or a `restore-keys` fallback into the newest generation,
and no run asked for an entry that was gone. So the store's older generations cost nothing to
delete, while its least-recently-used eviction was choosing what a run still needs.

The rule, in ``entries_to_prune``:

- Only ``refs/heads/main`` is pruned; a branch's entries go when the branch does, through the sweep.
- A family is a key with its trailing ``-<hash>`` removed, and an optional ``-v<n>`` suffix with it.
  A key without that shape is left alone, because its generations cannot be told apart.
- The newest ``--keep`` entries of a family stay (two by default, so a run whose inputs match the
  generation before the newest still hits it exactly) and the older ones are deleted.

``--dry-run`` prints what would be deleted. Exits 1 when a listing or a deletion failed.
"""

from __future__ import annotations

import argparse
import importlib.util
import os
import re
import sys
from pathlib import Path

# The sweep owns the paged listing, and the drain owns the API plumbing and the deletion. Both are
# loaded by path because their names have a dash.
_HERE = Path(__file__).resolve().parent


def _load(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, _HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


drain_caches = _load("drain_caches", "drain-caches.py")
sweep_caches = _load("sweep_caches", "sweep-caches.py")

MAIN_REF = "refs/heads/main"
# A key's last field is the hash of its inputs, 64 hex characters today; the length is not pinned
# because a family is recognised by the shape and not by one hash function.
HASH_SUFFIX = re.compile(r"^(.*)-[0-9a-f]{8,}$")
VERSION_SUFFIX = re.compile(r"-v\d+$")
DEFAULT_KEEP = 2


def family(key: str) -> str | None:
    """The key without its generation hash, or None when the key does not carry one."""
    match = HASH_SUFFIX.match(VERSION_SUFFIX.sub("", key))
    return match.group(1) if match else None


def entries_to_prune(entries: list[dict], keep: int = DEFAULT_KEEP) -> list[dict]:
    """The generations of main that a newer entry of the same family has made unreachable."""
    keep = max(1, keep)
    families: dict[str, list[dict]] = {}
    for entry in entries:
        if entry.get("ref") != MAIN_REF:
            continue
        name = family(entry.get("key") or "")
        if name is None:
            continue
        families.setdefault(name, []).append(entry)
    doomed = []
    for generations in families.values():
        if len(generations) <= keep:
            continue
        # The cache service answers a restore-keys match with the newest entry, so the newest are
        # the ones a run can still get. The id breaks a tie between entries of the same second.
        generations.sort(key=lambda e: (e.get("created_at") or "", e["id"]), reverse=True)
        doomed.extend(generations[keep:])
    # Oldest first, so the log reads in the order the generations were written.
    doomed.sort(key=lambda e: (e.get("created_at") or "", e["id"]))
    return doomed


def prune(api_base: str, slug: str, token: str, dry_run: bool, keep: int) -> int:
    """List, decide, delete, and return the exit code."""
    base = f"{api_base}/repos/{slug}"
    # Caches first: anything written after this listing is not in it, so it cannot be pruned.
    entries = sweep_caches.paged(f"{base}/actions/caches", token, "actions_caches", {})
    doomed = entries_to_prune(entries, keep)
    action = "would delete" if dry_run else "deleted"
    print(
        f"prune-caches: {len(entries)} entries, keeping the newest {keep} per main family,"
        f" pruning {len(doomed)}"
    )
    per_family: dict[str, list[int]] = {}
    gone = failures = 0
    for entry in doomed:
        if not dry_run:
            try:
                if not drain_caches.delete_cache(api_base, slug, entry["id"], token):
                    gone += 1
                    continue
            except Exception as e:  # noqa: BLE001 - one failed entry still prunes the rest
                failures += 1
                print(
                    f"prune-caches: {entry['key']}: {entry['id']} could not be deleted:"
                    f" {type(e).__name__}: {e}",
                    file=sys.stderr,
                )
                continue
        stats = per_family.setdefault(family(entry["key"]) or entry["key"], [0, 0])
        stats[0] += 1
        stats[1] += entry["size_in_bytes"]
    for name, (count, size) in sorted(per_family.items()):
        print(f"prune-caches: {name}: {count} entries, {drain_caches.megabytes(size)} {action}")
    total = sum(s[1] for s in per_family.values())
    print(
        f"prune-caches: {sum(s[0] for s in per_family.values())} entries,"
        f" {drain_caches.megabytes(total)} {action}, {gone} already gone"
    )
    return 1 if failures else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("--dry-run", action="store_true", help="delete nothing, print the plan")
    parser.add_argument(
        "--keep",
        type=int,
        default=DEFAULT_KEEP,
        help=f"how many of a family's newest generations to keep (default: {DEFAULT_KEEP})",
    )
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
        print("prune-caches: deleted nothing: GITHUB_REPOSITORY is not set", file=sys.stderr)
        return 1
    try:
        return prune(
            args.api_base,
            args.repo_slug,
            os.environ.get("GITHUB_TOKEN", ""),
            args.dry_run,
            args.keep,
        )
    except Exception as e:  # noqa: BLE001 - a prune that cannot read the API has not finished
        print(f"prune-caches: stopped: {type(e).__name__}: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
