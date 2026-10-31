#!/usr/bin/env python3
"""Print whether a pull request has to run the reference job (#631), ``true`` or ``false``.

    reference-needed.py <base> <head> [--repo <dir>]

The reference job runs BuildCraft 2.2.14 itself on 1.2.5 Forge, driven by the mod's own tests
(.github/workflows/build.yml). What it observes is the harness that job assembles: buildcraft's
about.json, its LICENSE, its screenshots/ and only the tests that capture a screenshot or branch on
Test.is_reference(). Its code, data and assets are deliberately not staged, since the real BuildCraft
jar provides every block, so a change to them cannot change what the reference run draws. The job
also observes the 1.2.5-forge loader and core it runs in, and whatever else the game and the job read
that is not a mod or another loader: shared/, mod_api.json, test-saves/, recreation.py, the scripts,
the Gradle files, the workflow. It loads no other mod, and no other loader.

So the answer is ``false`` only when every changed path is inside a mod directory other than
buildcraft or inside the directory of a loader other than 1.2.5-forge. Every doubt answers ``true``,
which only runs the job: a path anywhere else, a file directly in mods/ or loaders/, an empty diff
(more likely a mistake than a no-op), a diff that cannot be computed (an unreachable base, a failing
git) and any error in this script. Renames are off in the diff, so a file moved out of buildcraft or
out of 1.2.5-forge counts at its old path. The reasons go to stderr, so the CI log says why.

A run that is not a pull request never asks: the workflow runs the job on every push to main and
dispatch.
"""

from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path, PurePosixPath

# The mod and the loader the reference job is made of.
REFERENCE_MOD = "buildcraft"
REFERENCE_LOADER = "1.2.5-forge"


def load_affected_mods():
    """affected-mods.py, so both scripts read a diff and a tree the same way."""
    path = Path(__file__).resolve().parent / "affected-mods.py"
    spec = importlib.util.spec_from_file_location("affected_mods", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_changed_paths():
    return load_affected_mods().changed_paths


# The buildcraft files the reference job stages, as the workflow's "Assemble the reference harness"
# step copies them. A path outside them is not in the harness, so a change to it cannot change the
# reference run.
HARNESS_FILES = ("about.json", "LICENSE")
HARNESS_DIR = "screenshots/"
# The tests the harness takes: the ones `grep -l -e Test.screenshot -e Test.is_reference` selects.
HARNESS_TEST_MARKERS = (b"Test.screenshot", b"Test.is_reference")


def harness_paths(repo: Path, ref: str) -> set[str]:
    """The buildcraft paths the reference harness holds at ref, relative to the mod directory."""
    affected = load_affected_mods()
    entries = affected.tree_files(repo, ref).get(REFERENCE_MOD, [])
    contents = affected.read_blobs(repo, {blob for _, blob in entries})
    observed = set()
    for path, blob in entries:
        if path in HARNESS_FILES or path.startswith(HARNESS_DIR):
            observed.add(path)
        elif path.startswith("tests/") and any(
            marker in contents[blob] for marker in HARNESS_TEST_MARKERS
        ):
            observed.add(path)
    return observed


def in_harness(path: str, harness: set[str]) -> bool:
    prefix = f"mods/{REFERENCE_MOD}/"
    return path.startswith(prefix) and path[len(prefix) :] in harness


def reason_to_run(path: str, harness: set[str]) -> str | None:
    """Why this changed path can change what the reference job observes, or None when it cannot."""
    parts = PurePosixPath(path).parts
    if in_harness(path, harness):
        return f"{path} is staged into the reference harness"
    if len(parts) >= 3 and parts[0] == "mods":
        return None
    if len(parts) >= 3 and parts[0] == "loaders" and parts[1] != REFERENCE_LOADER:
        return None
    if len(parts) >= 3 and parts[0] == "loaders":
        return f"{path} is in loaders/{parts[1]}, the loader the reference job runs on"
    return f"{path} is not inside another mod's or another loader's directory"


def needed(repo: Path, base: str, head: str) -> tuple[bool, str]:
    paths = load_changed_paths()(repo, base, head)
    if not paths:
        return True, "the diff is empty, which is more likely a mistake than a no-op"
    harness = harness_paths(repo, base) | harness_paths(repo, head)
    for path in paths:
        reason = reason_to_run(path, harness)
        if reason is not None:
            return True, reason
    return False, (
        f"all {len(paths)} changed files are outside the reference harness, "
        "which loads no mod's code and no other loader"
    )


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("base", help="the commit the pull request is compared with")
    parser.add_argument("head", help="the commit that is built")
    parser.add_argument("--repo", default=".", help="the git repository (default: the current one)")
    args = parser.parse_args(argv)
    try:
        run, reason = needed(Path(args.repo), args.base, args.head)
    except Exception as e:  # noqa: BLE001 - whatever went wrong, the answer is to run the job
        run, reason = True, f"{type(e).__name__}: {e}"
    verdict = "running" if run else "skipping"
    print(f"reference-needed: {verdict} the reference job: {reason}", file=sys.stderr)
    print("true" if run else "false")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
