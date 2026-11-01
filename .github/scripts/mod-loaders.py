#!/usr/bin/env python3
"""Print the mods whose tests a loader runs, read from each mod's top-level ``loaders`` (#910).

    mod-loaders.py <loader> [<mod>...] [--mods-dir <dir>]

prints one comma separated line, such as ``buildcraft,coverage``, holding the mods among the
arguments that are not declared to leave this loader out. A mod whose about.json declares a loader
set that does not name this loader is left out, with the reason on stderr: the loader cannot draw
that mod's blocks, its goldens or its rendering, and its own test selection has no other way to say
so. A mod is kept when the declaration cannot answer: no about.json, one that does not parse, or a
set naming something that is not a ``loaders/`` directory. A stale name would otherwise drop the mod
on every loader while the job still passed, having run none of its tests.

The loaders a mod declares are its support set, not a claim the coverage gate trusts: mods/coverage
declares nothing, so the gate's own tests still run on every loader.

This is the whole test-side selection: run-loader.sh passes it every mod directory (or every name
GRUG_CI_MODS asked for) before it counts the tests, so a job for an unsupported loader expects only
the tests it will actually see. affected-loaders.py reads the same declarations to add the loaders a
selected mod would otherwise run nowhere on, which is the loader side of the same field.

The rule that an absent field means every loader lives in recreation.py's ``declared_loaders``, the
module that reads about.json for the rest of the tooling, so the two cannot disagree.
"""

from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def root_module(name: str):
    """A module at the repository root, so a declaration is read as the rest of the tooling reads it."""
    spec = importlib.util.spec_from_file_location(name, ROOT / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


recreation = root_module("recreation")

# Re-exported so a caller holding an already parsed about.json (affected-loaders.py) reads the rule
# through one import rather than a second copy of it.
declared_loaders = recreation.declared_loaders


def check(
    mods_dir: Path, loaders_dir: Path, loader: str, mods: list[str]
) -> tuple[list[str], list[str]]:
    """The mods to run and one line per mod left out, in the order the mods were given.

    A mod is kept when its declaration cannot answer: no about.json, one that does not parse, or a
    set naming something that is not a directory under ``loaders_dir``. A stale or misspelled name
    would otherwise leave the mod out on every loader, and a job that ran none of its tests would
    still pass, so the answer to a declaration this cannot trust is to run the mod everywhere.
    """
    kept = []
    lines = []
    for mod in mods:
        about_path = mods_dir / mod / "about.json"
        if not about_path.is_file():
            kept.append(mod)
            continue
        try:
            declared = recreation.loaders_of(about_path)
        except Exception as error:  # noqa: BLE001 - whatever is wrong with the file, the mod runs
            lines.append(f"{about_path} does not parse ({error}), so {mod} runs on every loader")
            kept.append(mod)
            continue
        if declared is None:
            kept.append(mod)
            continue
        unknown = sorted(name for name in declared if not (loaders_dir / name).is_dir())
        if unknown:
            lines.append(
                f"{mod} declares {', '.join(unknown)}, which is no directory under"
                f" {loaders_dir.name}/, so {mod} runs on every loader"
            )
            kept.append(mod)
            continue
        if loader in declared:
            kept.append(mod)
            continue
        lines.append(
            f"{mod} declares {', '.join(sorted(declared))}, which is not {loader},"
            f" so its tests do not run on {loader}"
        )
    return kept, lines


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("loader", help="the loader directory the run is for")
    parser.add_argument("mods", nargs="*", help="the mod directory names to choose among")
    parser.add_argument(
        "--mods-dir",
        type=Path,
        default=Path("mods"),
        help="the directory the mods are in (default: mods)",
    )
    parser.add_argument(
        "--loaders-dir",
        type=Path,
        default=ROOT / "loaders",
        help="the repository's loaders/ directory (default: the one in this script's repository)",
    )
    args = parser.parse_intermixed_args(argv)

    kept, lines = check(args.mods_dir, args.loaders_dir, args.loader, args.mods)
    for line in lines:
        print(f"mod-loaders: {line}", file=sys.stderr)
    # Silent when the declaration drops nothing, so a run where no mod declares a loader keeps the
    # log it had; a line here is what tells a reader the selection was narrowed.
    if lines:
        print(
            f"mod-loaders: {args.loader} runs the tests of {', '.join(kept) if kept else 'no mod'}"
            f" ({len(kept)} of {len(args.mods)})",
            file=sys.stderr,
        )
    # An explicit newline: Windows would otherwise write the line ending as \r\n, and the shell
    # reads this line as a comma separated list, so the last name would carry a carriage return.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(newline="\n")
    sys.stdout.write(",".join(kept) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
