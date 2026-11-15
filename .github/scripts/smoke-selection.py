#!/usr/bin/env python3
"""Choose the smoke games a run boots, as build.yml's smoke matrix (#1344).

    smoke-selection.py <event> <base> [--repo <dir>] [--head <commit>]

prints a JSON list, such as ``[{"game": "Forge 1.16.5", "recipe": "forge-1.16.5"}]``. The reasons go
to stderr, so the discover job's log says why a run booted the games it did.

A smoke game boots the universal jar under one mechanism a mod loader reads a jar by
(universal_jar.py's ``MECHANISMS``). A push to main whose diff is confined to the code of some
loaders, and of the files those loaders compile, can only change what the games of those loaders'
mechanisms see, so only those games boot. The rule resolves every doubt to running more games:

- a diff path inside a mod is ignored, since main-selection.py already skips every game for a diff
  that holds nothing else (#909);
- a path in ``loaders/<x>/`` selects the mechanism of ``<x>``, and a path in a directory a loader's
  build compiles from outside its own (``shared/ornithe``) selects each loader that compiles it;
- every other path (core/, shared/ code no loader declares, mod_api.json, the workflow, a script, a
  file straight in mods/) runs every game;
- a loader no game names has no mechanism to select, so it runs every game;
- a pull request, a dispatch, the weekly schedule, an empty or unreadable diff and any error run
  every game. A mechanism no loader of the repository provides (FML 1.6.4 and the like) boots
  only in those full runs, and a loader whose mechanism has no smoke game (1.2.5-forge) boots none,
  which is an empty list that build.yml turns into a skipped job rather than a matrix GitHub refuses.

The ``layer`` jobs are not narrowed: they build the universal jar every game boots.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
from functools import lru_cache
from pathlib import Path, PurePosixPath

HERE = Path(__file__).resolve().parent
LOADERS = "loaders"
MODS = "mods"


@lru_cache(maxsize=None)
def sibling(name: str):
    """One of the scripts beside this one, so every script reads a diff and a tree the same way."""
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), HERE / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def reached(repo: Path, base: str, head: str, loaders: list[str]) -> tuple[set[str] | None, str]:
    """The loaders a diff is confined to, or None when it holds a path no loader owns."""
    paths = sibling("affected-mods").changed_paths(repo, base, head)
    if not paths:
        return None, "the diff is empty, which is more likely a mistake than a no-op"
    compiling: dict[str, set[str]] = {}
    for loader, dirs in sibling("affected-loaders").shared_dirs(repo, head).items():
        for directory in dirs:
            compiling.setdefault(directory, set()).add(loader)
    found: set[str] = set()
    for path in paths:
        parts = PurePosixPath(path).parts
        if len(parts) >= 3 and parts[0] == MODS:
            continue
        if len(parts) >= 3 and parts[0] == LOADERS and parts[1] in loaders:
            found.add(parts[1])
            continue
        users = {u for d, us in compiling.items() if path.startswith(d + "/") for u in us}
        if not users:
            return None, f"{path} is outside every loader's own code"
        found |= users
    if not found:
        return None, "the diff reaches no loader"
    return found, f"the diff is confined to {', '.join(sorted(found))}"


def select(repo: Path, event: str, base: str, head: str = "HEAD") -> tuple[list[dict], str]:
    """The smoke matrix of a run and the reason for it."""
    universal_jar = sibling("universal_jar")
    full = universal_jar.smoke_matrix()
    if event != "push" or not base:
        return full, f"a {event} run with no diff to narrow by boots every game"
    loaders = sorted(sibling("added-loaders").tree_loaders(repo, head))
    found, reason = reached(repo, base, head, loaders)
    if found is None:
        return full, f"{reason}, so every game boots"
    games = sibling("affected-loaders").game_of(sorted(found))
    if set(found) - set(games):
        missing = ", ".join(sorted(set(found) - set(games)))
        return full, f"{reason}, but no game names {missing}, so every game boots"
    kinds = {game.kind for game in games.values()}
    chosen = [
        {"game": game.name, "recipe": game.smoke}
        for game in universal_jar.GAMES
        if game.smoke and game.kind in kinds
    ]
    return chosen, f"{reason}, which belong to {', '.join(sorted(kinds))}: {len(chosen)} games boot"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("event", help="github.event_name")
    parser.add_argument("base", nargs="?", default="", help="the commit the push started from")
    parser.add_argument("--repo", default=".", help="the git repository (default: the current one)")
    parser.add_argument("--head", default="HEAD", help="the commit that is built (default: HEAD)")
    args = parser.parse_args(argv)
    try:
        matrix, reason = select(Path(args.repo), args.event, args.base, args.head)
    except Exception as e:  # noqa: BLE001 - whatever went wrong, every game boots
        matrix = sibling("universal_jar").smoke_matrix()
        reason = f"{type(e).__name__}: {e}, so every game boots"
    print(f"smoke-selection: {reason}", file=sys.stderr)
    print(json.dumps(matrix))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
