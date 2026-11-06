#!/usr/bin/env python3
"""Resolve the base a workflow_dispatch names to the full commit SHA it is (#1170).

    dispatch-base.py <base> [--repo <dir>] [--repo-slug <owner/repo>]

prints the full 40-character SHA of the commit ``base`` names, and nothing for an empty base, which
is the full run a release and the four-hourly check send. The reason goes to stderr, so the discover
job's log says how the base was read.

``base`` is what a person typed into ``gh workflow run build.yml -f base=``, and each shape takes the
route that shape can take:

- a full 40-character SHA is the commit itself, so it is printed unchanged and nothing is asked;
- a hexadecimal prefix is a commit the one-commit-deep checkout does not hold, and no fetch can name
  a commit by prefix, so it is resolved through the GitHub API, whose commits endpoint answers a
  prefix of four characters or more with the full SHA;
- anything else is a branch, a tag or a remote-tracking name, which the checkout's own refs answer
  when they hold it and origin answers when they do not, fetched one commit deep, since that is all
  a diff needs.

A base that names no commit fails, with the reason, rather than printing nothing: the caller then
fails the dispatch, because a comparison whose base is silently empty runs every job the comparison
exists to skip, so a typo would spend a full run (#1170). An empty base is not that case: it is the
full run a release and the four-hourly check send, and it says so.

The commit itself is not fetched here: the step that builds on this answer fetches the full SHA the
same way it fetches a full SHA typed by hand, and a checkout that cannot fetch it fails there. The
API is read with ``gh api``, which takes the workflow's token from GH_TOKEN in that step's
environment.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

# git abbreviates a commit to at least four characters, and the commits endpoint resolves a prefix
# that short, so a hexadecimal base below that length is read as a name instead: it cannot name a
# commit by prefix, and a branch may be called "abc".
FULL_SHA = re.compile(r"[0-9a-fA-F]{40}")
PREFIX = re.compile(r"[0-9a-fA-F]{4,39}")


class Unresolved(Exception):
    """A base that names no commit this run can read."""


def command(args: list[str], repo: Path | None = None) -> subprocess.CompletedProcess:
    """Run a command, reporting its own words rather than raising on a non-zero exit."""
    line = ["git", "-C", str(repo), *args] if repo else args
    return subprocess.run(line, capture_output=True, text=True)


def detail(result: subprocess.CompletedProcess, who: str) -> str:
    """The words a failed command left, for a message that says what it said."""
    words = " ".join(result.stderr.split())
    return words or f"{who} exited {result.returncode} without saying why"


def api_commit_sha(prefix: str, slug: str) -> str:
    """The full SHA the GitHub API resolves a hexadecimal prefix to, or raises Unresolved."""
    if not slug:
        raise Unresolved(
            "this step has no GITHUB_REPOSITORY, which names the repository to ask the API about"
        )
    result = command(["gh", "api", f"repos/{slug}/commits/{prefix}", "--jq", ".sha"])
    if result.returncode != 0:
        raise Unresolved(
            f"the API resolved no commit for the prefix '{prefix}' ({detail(result, 'gh')})"
        )
    sha = result.stdout.strip()
    if not FULL_SHA.fullmatch(sha):
        raise Unresolved(
            f"the API answered '{sha}' for the prefix '{prefix}', which is no full commit SHA"
        )
    sha = sha.lower()
    if not sha.startswith(prefix.lower()):
        raise Unresolved(
            f"the API answered '{sha}' for the prefix '{prefix}', which does not start with it"
        )
    return sha


def branch_sha(repo: Path, name: str) -> str:
    """The commit a branch name names: the checkout's own refs, else origin's, fetched one deep."""
    local = command(["rev-parse", "--verify", "--quiet", f"{name}^{{commit}}"], repo)
    if local.returncode == 0 and local.stdout.strip():
        return local.stdout.strip()
    # A fetch writes FETCH_HEAD and nothing else, so the commit a successful fetch brings in is read
    # from there: the name itself is a ref this checkout does not have.
    remote = name[len("origin/") :] if name.startswith("origin/") else name
    fetched = command(["fetch", "--quiet", "--no-tags", "--depth=1", "origin", remote], repo)
    if fetched.returncode != 0:
        raise Unresolved(
            f"the checkout holds no commit '{name}', and origin has no branch '{remote}' to fetch"
            f" ({detail(fetched, 'git fetch')})"
        )
    head = command(["rev-parse", "--verify", "--quiet", "FETCH_HEAD^{commit}"], repo)
    sha = head.stdout.strip()
    if head.returncode != 0 or not sha:
        raise Unresolved(
            f"origin's '{remote}' was fetched, but this checkout cannot read the commit it names"
        )
    return sha


def resolve(repo: Path, base: str, slug: str = "") -> tuple[str, str]:
    """The full SHA this base names, or "" for no base, and the sentence that says which shape it was."""
    if not base:
        return "", (
            "no base was named, so this dispatch is the full run a release and the four-hourly"
            " check send"
        )
    if FULL_SHA.fullmatch(base):
        return base.lower(), "a full commit SHA, which is the commit itself"
    if PREFIX.fullmatch(base):
        return api_commit_sha(base, slug), (
            "an abbreviated SHA, which no fetch can name, so the API resolved it to the commit it"
            " abbreviates"
        )
    return branch_sha(repo, base), (
        f"the name '{base}', which this checkout or origin resolved to the commit it names"
    )


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("base", help="the commit a dispatch compares its branch with")
    parser.add_argument("--repo", default=".", help="the git repository (default: the current one)")
    parser.add_argument(
        "--repo-slug",
        default=os.environ.get("GITHUB_REPOSITORY", ""),
        help="owner/repo, from the GITHUB_REPOSITORY environment (default: the environment)",
    )
    args = parser.parse_args(argv)
    try:
        sha, reason = resolve(Path(args.repo), args.base, args.repo_slug)
    except Unresolved as e:
        print(
            f"dispatch-base: the base '{args.base}' names no commit, so this dispatch fails instead"
            f" of spending a full run on a comparison it cannot make: {e}",
            file=sys.stderr,
        )
        return 1
    except Exception as e:  # noqa: BLE001 - a base this cannot read is a dispatch that must not run
        print(
            f"dispatch-base: the base '{args.base}' cannot be resolved ({type(e).__name__}: {e}),"
            " so this dispatch fails instead of spending a full run on it",
            file=sys.stderr,
        )
        return 1
    if sha:
        print(
            f"dispatch-base: the base '{args.base}' is the commit {sha}, {reason}", file=sys.stderr
        )
    else:
        print(f"dispatch-base: {reason}", file=sys.stderr)
    print(sha)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
