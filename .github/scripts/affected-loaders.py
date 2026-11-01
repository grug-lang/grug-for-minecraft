#!/usr/bin/env python3
"""Print the loader jobs a pull request has to run, as build.yml's build matrix (#637).

    affected-loaders.py <base> <head> [--repo <dir>]

prints a JSON list, such as ``["1.2.5-forge", "1.20.6-forge", "b1.7.3-stationapi"]``. The reasons go
to stderr, so the CI log shows which loaders a pull request selected and why.

Every loader job costs a runner slot, and the plan allows 20 concurrent jobs shared by the whole
organisation, so a pull request runs the loaders its diff can reach plus one representative per
family. A push to main and a dispatch run every loader, and neither asks this script.

The rule is sound by construction, and every doubt resolves to running more, never fewer:

- a diff confined to ``loaders/<x>/`` adds ``<x>`` and the loaders that share its code (the two
  Ornithe loaders through ``shared/ornithe``, which both of their builds compile);
- a diff confined to a directory a loader's build compiles from outside its own adds the loaders
  that compile it;
- every family's representative runs whatever the diff holds, because a path outside the loaders'
  own code (core/, mod_api.json, mods/, test-saves/, the scripts, the workflow) can reach every
  loader, and because the coverage gate measures core's classes through every mechanism: measured on
  run 41258615457, a report over ``1.20.6-forge`` and ``b1.7.3-ornithe`` alone misses 10 instructions
  of core's generated ``HostFunctions`` that only ``b1.7.3-stationapi`` reaches, and a report without
  ``1.20.6-forge`` misses ``GrugForgeMod``. So the representatives are what keep the gate whole;
- an empty diff (more likely a mistake than a no-op), a diff that cannot be computed (an unreachable
  base, a failing git) and any error in this script run every loader.

A family is one mechanism a mod loader reads a jar by, universal_jar.py's ``MECHANISMS``: the
vocabulary the smoke jobs already keep one unsupported game per (#493), so a new mechanism extends
one table rather than adding a second. Its representative is the loader running the newest Minecraft
and, among equals, the one no other loader shares code with, since code two loaders of one family
share is exercised either way. The rest of the tie goes to the first directory name, so the choice is
stable and a loader added later can take the family over. A loader no game names yet is a family of
its own, and so runs on every pull request until a game names it, the direction that runs more.

A break only a skipped loader has is found by the next push to main, which runs every loader, or by a
dispatch of the branch, and the job it fails is attributable to the merge that carried it.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import posixpath
import re
import sys
from functools import lru_cache
from pathlib import Path, PurePosixPath

HERE = Path(__file__).resolve().parent
# A loader's own code is its directory; what it compiles from outside it is code it shares.
LOADERS = "loaders"
# The files a loader's build declares its source sets in.
BUILD_FILES = ("build.gradle", "build.gradle.kts", "root.gradle")
# A source directory a build declares, which is a literal path rather than a variable when it names
# code outside the loader's own directory (1.2.5's generated sources are a variable, and inside it).
SRC_DIR = re.compile(r"srcDir\s*\(?\s*['\"]([^'\"]+)['\"]")
# Gradle's two comment forms, since a path named in prose is not a source directory.
COMMENT = re.compile(r"/\*.*?\*/|//[^\n]*", re.S)


@lru_cache(maxsize=None)
def sibling(name: str):
    """One of the scripts beside this one, so every script reads a diff and a tree the same way."""
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), HERE / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def loader_dirs(root: Path) -> list[str]:
    """The loaders in the working tree, as the workflow's discover step reads them."""
    return sorted(p.name for p in (root / LOADERS).iterdir() if p.is_dir())


def shared_dirs(repo: Path, ref: str) -> dict[str, set[str]]:
    """For each loader at ``ref``, the directories outside its own that its build compiles.

    A build file names its extra source directories literally, as ``srcDir '../../shared/x/src'``,
    just as the two Ornithe builds name ``shared/ornithe``. That declaration is what decides which
    loaders a change to the shared directory reaches, so it is read rather than listed here: a
    loader that gains one is in, and one that loses it is out, without a table to forget.
    """
    affected = sibling("affected-mods")
    out = affected.git(repo, "ls-tree", "-r", "-z", ref, "--", LOADERS)
    wanted: dict[tuple[str, str], str] = {}
    for entry in out.split(b"\0"):
        if not entry:
            continue
        meta, _, raw_path = entry.partition(b"\t")
        _, kind, blob = meta.decode().split()
        parts = PurePosixPath(raw_path.decode(errors="surrogateescape")).parts
        if len(parts) == 3 and parts[0] == LOADERS and kind == "blob" and parts[2] in BUILD_FILES:
            wanted[(parts[1], parts[2])] = blob
    contents = affected.read_blobs(repo, set(wanted.values()))
    dirs: dict[str, set[str]] = {}
    for (loader, _), blob in wanted.items():
        text = COMMENT.sub("", contents[blob].decode(errors="replace"))
        for declared in SRC_DIR.findall(text):
            path = posixpath.normpath(f"{LOADERS}/{loader}/{declared}")
            if not path.startswith(f"{LOADERS}/{loader}/"):
                dirs.setdefault(loader, set()).add(path)
    return dirs


def game_of(loaders: list[str], games=None) -> dict:
    """For each loader, the game that runs it, the newest Minecraft when a game list names two."""
    universal_jar = sibling("universal_jar")
    games = universal_jar.GAMES if games is None else games
    found = {}
    for game in games:
        if game.expect in loaders:
            current = found.get(game.expect)
            if (
                current is None
                or universal_jar.compare_semver(game.minecraft, current.minecraft) > 0
            ):
                found[game.expect] = game
    return found


def representatives(loaders: list[str], games=None, shares: dict[str, set[str]] | None = None):
    """The loader representing each family: family -> loader.

    A family is the mechanism a mod loader reads a jar by, and its representative is the loader
    running the newest Minecraft. Among equals the one no other loader shares code with, since the
    other's copy of that code is exercised either way, and then the first directory name, so the
    choice is stable, and a loader no game names is a family of its own, which runs rather than being
    dropped.
    """
    universal_jar = sibling("universal_jar")
    found = game_of(loaders, games)
    shares = {} if shares is None else shares
    # A loader that shares code with another ranks below one that does not, at the same Minecraft.
    newest: dict[str, tuple[str, bool, str]] = {}
    for loader in sorted(loaders):
        game = found.get(loader)
        family = game.kind if game is not None else loader
        minecraft = game.minecraft if game is not None else "0"
        alone = not shares.get(loader)
        best = newest.get(family)
        if best is None:
            newest[family] = (minecraft, alone, loader)
            continue
        order = universal_jar.compare_semver(minecraft, best[0])
        if order > 0 or (order == 0 and alone and not best[1]):
            newest[family] = (minecraft, alone, loader)
    return {family: loader for family, (_, _, loader) in newest.items()}


def describe(paths: list[str]) -> str:
    shown = ", ".join(paths[:3])
    return shown + (f" and {len(paths) - 3} more" if len(paths) > 3 else "")


def select(repo: Path, base: str, head: str, games=None) -> tuple[list[str], list[str]]:
    """The loaders to run, sorted, and one line per reason for the CI log."""
    loaders = sorted(sibling("added-loaders").tree_loaders(repo, head))
    current = set(loaders)
    paths = sibling("affected-mods").changed_paths(repo, base, head)
    if not paths:
        return loaders, ["the diff is empty, which is more likely a mistake than a no-op"]

    # The directories a loader compiles, so a shared directory the diff deletes still reaches the
    # loader that compiled it, since deleting its files does not change the build that includes it.
    # A loader the head tree no longer holds is left out: its job cannot run, and its own diff runs
    # the representatives.
    compiling: dict[str, set[str]] = {}
    for loader, dirs in shared_dirs(repo, head).items():
        if loader in current:
            for directory in dirs:
                compiling.setdefault(directory, set()).add(loader)
    shares: dict[str, set[str]] = {loader: set() for loader in loaders}
    for users in compiling.values():
        for loader in users:
            shares[loader].update(users - {loader})

    family_of = representatives(loaders, games, shares)
    chosen: set[str] = set(family_of.values())
    chosen &= current
    reasons: dict[str, list[str]] = {}
    for path in paths:
        parts = PurePosixPath(path).parts
        if len(parts) >= 3 and parts[0] == LOADERS and parts[1] in loaders:
            owner = parts[1]
            chosen.add(owner)
            chosen.update(shares[owner])
            others = shares[owner]
            reasons.setdefault(
                f"loaders/{owner} is a loader's own code"
                + (f", which {', '.join(sorted(others))} share" if others else ""),
                [],
            ).append(path)
            continue
        compilers = sorted(
            {
                loader
                for d in compiling
                if d == path or path.startswith(d + "/")
                for loader in compiling[d]
            }
        )
        if compilers:
            chosen.update(compilers)
            reasons.setdefault(f"the diff is in code {', '.join(compilers)} compile", []).append(
                path
            )
            continue
        reasons.setdefault(
            "a changed path is outside every loader's own code, so each family's representative runs",
            [],
        ).append(path)

    lines = [f"{line}: {describe(group)}" for line, group in reasons.items()]
    families = ", ".join(
        f"{loader} ({family})" for family, loader in sorted(family_of.items(), key=lambda f: f[1])
    )
    lines.append(f"the representatives always run: {families}")
    if not chosen:  # no loader exists, which an empty matrix would turn into no jobs
        return loaders, lines + ["no loader could be selected, so every loader runs"]
    return sorted(chosen), lines


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("base", help="the commit the pull request is compared with")
    parser.add_argument("head", help="the commit that is built")
    parser.add_argument("--repo", default=".", help="the git repository (default: the current one)")
    args = parser.parse_args(argv)
    try:
        selected, lines = select(Path(args.repo), args.base, args.head)
    except Exception as e:  # noqa: BLE001 - whatever went wrong, every loader runs
        try:
            selected = loader_dirs(Path(args.repo))
            lines = [f"{type(e).__name__}: {e}, so every loader runs"]
        except Exception as missing:  # noqa: BLE001 - there is no loader directory to run
            selected = []
            lines = [f"{type(e).__name__}: {e}, and {missing}"]

    for line in lines:
        print(f"affected-loaders: {line}", file=sys.stderr)
    print(f"affected-loaders: selecting {', '.join(selected) or 'no loader'}", file=sys.stderr)
    print(json.dumps(selected))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
