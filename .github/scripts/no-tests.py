#!/usr/bin/env python3
"""Print whether no test can read a diff, so the test jobs can skip (#785).

    no-tests.py <base> <head> [--repo <dir>]

prints ``true`` when every changed path is one no test reads, and ``false`` otherwise. True is what
lets the game and unit test jobs skip: their result cannot differ from the commit before. The reasons
go to stderr, so the CI log says why a run kept or dropped its tests.

The set is a whitelist, never a denylist. One path outside it runs every test, and so does an empty
diff (more likely a mistake than a no-op), a diff that cannot be computed (an unreachable base, a
failing git) and any error in this script.

The set is prose only:

- a ``*.md`` outside ``mods/``, and a file named ``README``. No test opens one. A ``.md`` inside a
  mod is not prose: a mod's own files are what its tests read, and GrugScreenshots walks a mod's
  screenshots/ and refuses a .md it finds there.
- ``.github/ISSUE_TEMPLATE/``, the forms GitHub fills in for a new issue, which only GitHub reads.

Everything else runs the tests, and the CI's own scripts least of all may skip: reference-needed.py
and watch-run.sh look like development-only files, but reference_needed_test.py and watch_run_test.py
import or run them in the core job, so a change to either has a test of its own that would fail.
mod_api.json, run-loader.sh, the test runner, the workflow and anything else a test runs with are
outside the set for the same reason.

README.md is in the set. On a push to main that skips core's universal-jar test, which reads it
(universal_jar_test.py, and only outside a pull request), but a release still cannot publish a README
that test would refuse: release.yml requires a successful build.yml dispatch, whose core job runs it,
and the next push to main runs it too.
"""

from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path, PurePosixPath

# A path inside one of these directories is read by nothing but GitHub.
ALLOWED_DIRS = (".github/ISSUE_TEMPLATE/",)
# A file with one of these names, anywhere outside mods/, is a readme.
ALLOWED_NAMES = ("README",)
ALLOWED_SUFFIXES = (".md",)
# A mod's files are what its tests read, including its text files.
MODS = "mods"


def load_affected_mods():
    """affected-mods.py, so both scripts read a diff the same way."""
    path = Path(__file__).resolve().parent / "affected-mods.py"
    spec = importlib.util.spec_from_file_location("affected_mods", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_changed_paths():
    return load_affected_mods().changed_paths


def reason_to_run(path: str) -> str | None:
    """Why a test can read this changed path, or None when none can."""
    parts = PurePosixPath(path).parts
    if not parts:
        return f"{path!r} names no file"
    if parts[0] == MODS:
        return f"{path} is inside a mod, whose files its tests read"
    if any(path.startswith(directory) for directory in ALLOWED_DIRS):
        return None
    if parts[-1] in ALLOWED_NAMES or PurePosixPath(parts[-1]).suffix in ALLOWED_SUFFIXES:
        return None
    return f"{path} is not prose any test can read"


def no_tests(repo: Path, base: str, head: str) -> tuple[bool, str]:
    """Whether no test can read the diff, and the reason for the answer."""
    paths = load_changed_paths()(repo, base, head)
    if not paths:
        return False, "the diff is empty, which is more likely a mistake than a no-op"
    for path in paths:
        reason = reason_to_run(path)
        if reason is not None:
            return False, reason
    return True, f"all {len(paths)} changed paths are prose no test reads: {', '.join(paths)}"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("base", help="the commit the diff is taken from")
    parser.add_argument("head", help="the commit that is built")
    parser.add_argument("--repo", default=".", help="the git repository (default: the current one)")
    args = parser.parse_args(argv)
    try:
        no_test, reason = no_tests(Path(args.repo), args.base, args.head)
    except Exception as e:  # noqa: BLE001 - whatever went wrong, every test runs
        no_test, reason = False, f"{type(e).__name__}: {e}"
    verdict = "skipping" if no_test else "running"
    print(f"no-tests: {verdict} the test jobs: {reason}", file=sys.stderr)
    print("true" if no_test else "false")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
