#!/usr/bin/env python3
"""Print the loaders whose dedicated-server leg the carried-over exec data forces to run (#1739).

    server-exec-needed.py <head> [--repo <dir>] [--base <loader>=<sha>]... [--already <json>]

The coverage job carries over the newest main run's ``server-exec-<loader>`` artifact for each loader
whose leg this run does not run (#1602), and JaCoCo counts a carried class only when its class id,
the CRC64 of the class bytes, matches the bytes staged now. A merge that changed a loader's classes
after that artifact was recorded leaves its data matching nothing: the class then counts as fully
missed, and a pull request or push whose own diff selects no leg fails the gate until a main run
records fresh data (#1735, #1739).

The artifact's run names the commit it built, so whether its recording still matches is a question
about the tree since then: a changed path under ``loaders/<loader>/``, or under a directory its build
compiles, changes that loader's class bytes. This script answers with each such loader, plus every
loader whose live artifact is missing or expired, since without a recording its classes have nothing
to be counted against. It reads no list of loaders or classes: the loaders are the ones
dedicated-needed.py answers for, and the paths are the ones its ``loaders_for_diff`` already maps to
an owner. A diff with no changed paths selects no leg: the recording was made at a tree HEAD does
not differ from. A loader whose commit cannot be read runs its leg, the doubt direction.

``--already`` takes the JSON list the run's own diff selected, and the answer is that list unioned
with the forced legs, sorted, so a loader both the run and this script choose appears once. ``--base``
names a loader and the head commit of the run whose artifact it carries; a loader it does not name
has no live artifact. The reasons go to stderr for the CI log, one line per loader.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
from pathlib import Path


def sibling(name: str):
    """One of the scripts beside this one, so every script reads a diff the same way."""
    spec = importlib.util.spec_from_file_location(
        name.replace("-", "_"), Path(__file__).resolve().parent / f"{name}.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def changed_loaders(repo: Path, base: str, head: str) -> tuple[list[str], str]:
    """The loaders whose code changed between a commit and the head, and the reason.

    An empty diff answers no loader: the recording was made at a tree that nothing since has
    changed, unlike the run's own diff, where an empty one is more likely a mistake than a no-op and
    dedicated-needed.py therefore runs every leg.
    """
    paths = sibling("affected-mods").changed_paths(repo, base, head)
    if not paths:
        return [], f"{base} and {head} hold the same tree"
    return sibling("dedicated-needed").loaders_for_diff(repo, base, head)


def select(
    repo: Path, head: str, bases: dict[str, str], already: list[str] | None = None
) -> tuple[list[str], list[str]]:
    """The loaders whose dedicated leg runs, and one line per loader saying why.

    ``bases`` maps a loader to the head commit of the run whose live ``server-exec-<loader>``
    artifact it carries, and a loader it does not name has none. ``already`` is the loaders the
    run's own diff selected, which are kept whatever the artifacts say.
    """
    dedicated = sibling("dedicated-needed")
    chosen = set(already or ())
    lines: list[str] = []
    for loader in dedicated.DISPATCH_LOADERS:
        base = bases.get(loader)
        if not base:
            chosen.add(loader)
            lines.append(
                f"{loader}: no live server-exec artifact is carried over, so its classes have"
                " nothing to count against and its leg runs"
            )
            continue
        try:
            moved, reason = changed_loaders(repo, base, head)
        except Exception as e:  # noqa: BLE001 - a commit this cannot read runs that loader's leg
            chosen.add(loader)
            lines.append(
                f"{loader}: the commit of its server-exec artifact, {base}, cannot be read"
                f" ({type(e).__name__}: {e}), so its leg runs"
            )
            continue
        if loader in moved:
            chosen.add(loader)
            lines.append(
                f"{loader}: its server-exec artifact was recorded at {base}, and since then"
                f" {reason}"
            )
        else:
            lines.append(
                f"{loader}: its server-exec artifact was recorded at {base}, and no server code"
                " changed since, so the carried data matches"
            )
    return sorted(chosen), lines


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("head", help="the commit that is built")
    parser.add_argument("--repo", default=".", help="the git repository (default: the current one)")
    parser.add_argument(
        "--base",
        action="append",
        default=[],
        metavar="LOADER=SHA",
        help="a loader and the head commit of the run whose server-exec artifact it carries",
    )
    parser.add_argument(
        "--already",
        default="[]",
        help="the JSON list the run's own diff already selected (default: [])",
    )
    args = parser.parse_args(argv)
    bases: dict[str, str] = {}
    for pair in args.base:
        loader, _, sha = pair.partition("=")
        if loader and sha:
            bases[loader] = sha
    try:
        already = json.loads(args.already)
        chosen, lines = select(Path(args.repo), args.head, bases, already)
    except Exception as e:  # noqa: BLE001 - whatever went wrong, every loader's leg runs
        chosen = sorted(set(sibling("dedicated-needed").DISPATCH_LOADERS))
        lines = [f"{type(e).__name__}: {e}, so every loader's leg runs"]
    for line in lines:
        print(f"server-exec-needed: {line}", file=sys.stderr)
    print(json.dumps(chosen))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
