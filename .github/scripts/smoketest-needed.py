#!/usr/bin/env python3
"""Print whether the smoketest job runs on a pull request (#1589), ``true`` or ``false``.

    smoketest-needed.py <base> <head> [--repo <dir>]

The smoketest job builds the C host under .github/smoketest/ on grug-rs's C API and runs the
``mods/coverage/`` logic tests on it. A porting agent runs it locally before pushing, so a pull
request runs it only to check the tool itself: when the diff touches ``.github/smoketest/``, whose
build, selftest and contract check then fail the pull request. Any other diff answers ``false``,
and the weekly dispatch reports how far the host has come. Every doubt answers ``false`` too: an
empty diff, a diff that cannot be computed and any error in this script. The reasons go to stderr,
so the CI log says why.
"""

from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path

READ = ".github/smoketest/"


def sibling(name: str):
    spec = importlib.util.spec_from_file_location(
        name.replace("-", "_"), Path(__file__).resolve().parent / f"{name}.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def needed(repo: Path, base: str, head: str) -> tuple[bool, str]:
    paths = sibling("affected-mods").changed_paths(repo, base, head)
    if not paths:
        return False, "the diff is empty"
    for path in paths:
        if path.startswith(READ):
            return True, f"{path} is part of the smoketest"
    return False, f"none of the {len(paths)} changed paths is part of the smoketest"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("base", help="the commit the diff is taken from")
    parser.add_argument("head", help="the commit that is built")
    parser.add_argument("--repo", default=".", help="the git repository (default: the current one)")
    args = parser.parse_args(argv)
    try:
        run, reason = needed(Path(args.repo), args.base, args.head)
    except Exception as e:  # noqa: BLE001 - whatever went wrong, the hint is skipped
        run, reason = False, f"{type(e).__name__}: {e}"
    print(
        f"smoketest-needed: {'running' if run else 'skipping'} the smoketest job: {reason}",
        file=sys.stderr,
    )
    print("true" if run else "false")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
