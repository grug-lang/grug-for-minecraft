#!/usr/bin/env python3
"""Print the loader directories a push adds, as the JSON list the Windows jobs run on (#632).

    added-loaders.py <before> [<head>] [--repo <dir>]

The Windows boot jobs run on a dispatch, which is the full run a release requires, and on a push to
main only for a loader that push adds: a change that boots on Linux cannot fail to boot on Windows
without failing on Linux first, and those two jobs are 219 s of a loader's 484 s on main while
running no tests (#707). A loader added without them would put a layer in the universal jar that
has never booted on Windows.

So the answer is the set of directories in loaders/ at the head tree minus the set at the commit the
push started from, sorted, and ``[]`` when nothing is new there, which leaves the Windows jobs no
matrix entry and so skips them. Two trees are compared rather than the diff's added files, because
a file added inside an existing loader is not a new loader and a directory moved into loaders/ is
one. Every doubt answers every loader, the direction that runs more: an unreadable commit (an
unreachable before, a failing git) and any error in this script. The reason goes to stderr, so the
CI log says why.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path, PurePosixPath


def loader_dirs(root: Path) -> list[str]:
    """The loaders in the working tree, as the workflow's discover step reads them."""
    return sorted(p.name for p in (root / "loaders").iterdir() if p.is_dir())


def git(root: Path, *args: str) -> bytes:
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True, check=False)
    if result.returncode != 0:
        raise RuntimeError(
            f"git {' '.join(args)} failed: {result.stderr.decode(errors='replace').strip()}"
        )
    return result.stdout


def tree_loaders(root: Path, ref: str) -> set[str]:
    """The loaders a tree holds: the directories directly in loaders/."""
    out = git(root, "ls-tree", "-d", "--name-only", "-z", ref, "--", "loaders/")
    names = set()
    for raw in out.split(b"\0"):
        parts = PurePosixPath(raw.decode(errors="surrogateescape")).parts
        if len(parts) == 2 and parts[0] == "loaders":
            names.add(parts[1])
    return names


def added_dirs(root: Path, before: str, head: str) -> list[str]:
    """The loaders the head tree holds and the before tree does not."""
    return sorted(tree_loaders(root, head) - tree_loaders(root, before))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("before", help="the commit the push started from")
    parser.add_argument(
        "head", nargs="?", default="HEAD", help="the commit the push ends (default: HEAD)"
    )
    parser.add_argument("--repo", default=".", help="the git repository (default: the current one)")
    args = parser.parse_args(argv)
    root = Path(args.repo)
    try:
        added = added_dirs(root, args.before, args.head)
        reason = f"this push adds {', '.join(added)}" if added else "this push adds no loader"
    except Exception as e:  # noqa: BLE001 - whatever went wrong, every loader runs
        added = loader_dirs(root)
        reason = (
            f"what this push adds cannot be read ({type(e).__name__}: {e}), "
            "so every loader runs"
        )
    print(f"added-loaders: {reason}", file=sys.stderr)
    print(json.dumps(added))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
