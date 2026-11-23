#!/usr/bin/env python3
"""Print whether a dispatch needs the dedicated-server legs (#1342), ``true`` or ``false``.

    dedicated-needed.py <base> <head> [--repo <dir>] [--event <event>] [--ref <ref>] [--loaders]

The ``multiplayer`` job runs the suite on a dedicated server with a client joined to it, which is
31% of a full dispatch's runner minutes (#1342). A proof or measurement dispatch names a ``base``,
and when its diff against that base is confined to the mods the answer is ``false``: a mod port's
grug code and tests are exercised by the client legs of every loader, and none of the server's
code, the runner, the adapters or the API changes. The answer is ``true`` for any other changed
path, in particular:

- ``core/``, ``mod_api.json``, ``loaders/``, ``test-saves/`` and ``.github/`` (the runner, the
  scripts, the workflows), which are the code and the harness the server legs run;
- ``mods/coverage/``, whose tests are the host function, loader and runner tests that the servers
  run;
- anything this script does not name, so a new top-level directory runs the legs by default.

Prose alone (a top-level ``*.md``) cannot change a server run either, so it counts as client-only
beside mods. Every doubt answers ``true``, which only runs the legs: an empty diff, a diff that
cannot be computed and any error in this script. The reasons go to stderr, so the CI log says why.

A dispatch on ``refs/heads/main`` and a dispatch with no base always answer ``true``: they are the
full runs the four-hourly check and the weekly release send.

A pull request and a push narrow the legs by loader instead (#1601): the dedicated-server exec data
of a loader whose server class lost its ``@GrugGenerated`` has to match the class bytes of the run
that counts it, and a pull request carries over the newest main run's exec data only for a class
whose bytes it did not change. So a leg runs on a pull request or a push exactly for each loader
whose own code the diff touches: a path under ``loaders/<x>/``, or under a directory ``<x>``'s
build compiles from outside its own (``shared/ornithe`` reaches both Ornithe loaders), which is
where every server class (``*Server*Adapter*``, ``GrugServerRun``, ``GrugControl*``,
``ServerGrugModLoader`` and the rest) lives. A change to ``core/``, ``mods/``, the scripts or the
workflow changes no server class's bytes, so it runs no leg and the carried-over data counts. The
exceptions run every loader's leg: any ``core/`` path but ``core/src/test/`` (some core lines only a
server leg executes), ``mod_api.json``, ``mods/coverage/``, the root build files (``gradle/``,
``gradle.properties``, ``build.gradle*``, ``settings.*``) and ``.github/actions/setup-loader-build/``. A path under ``shared/``
that no build declares, and every doubt (no base commit, an empty diff, a diff that cannot be
computed, any error) run every loader's leg, which only runs more. The schedule never runs the legs
(the job's own condition excludes it), so it answers every loader and nothing reads it.

``--loaders`` prints the JSON list of loaders whose legs run: every loader that has a dedicated
server for a dispatch (none when the dispatch skips them), and the narrowed list for a pull request
or a push. The default answer is ``true`` exactly when that list is not empty.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
from pathlib import Path

# A changed path under one of these is client-only unless CLIENT_ONLY_EXCEPTIONS names it.
CLIENT_ONLY_PREFIXES = ("mods/",)
# The mod whose tests are the server legs' own subject, so a change to it runs them.
CLIENT_ONLY_EXCEPTIONS = ("mods/coverage/",)
# Every loader with a dedicated server, which a dispatch runs and a doubt falls back to.
DISPATCH_LOADERS = (
    "1.2.5-forge",
    "1.20.6-forge",
    "a1.1.2_01-ornithe",
    "b1.7.3-ornithe",
    "b1.7.3-stationapi",
)
LOADERS = "loaders"
SHARED = "shared"
# A diff under one of these runs every loader's leg on a pull request and a push (#1601): core's
# classes are loaded by every server and some have lines only a server leg executes (GrugLinkArms,
# GrugServerJoin, GrugLightSync and the rest), the coverage mod's tests are what a carried exec was
# recorded from (a deleted or weakened test must not leave a stale carried exec), and the root build
# can change a loader's class bytes. core's own unit tests change no server run.
EVERY_LOADER_PREFIXES = (
    "core/",
    "mods/coverage/",
    "gradle/",
    ".github/actions/setup-loader-build/",
)
EVERY_LOADER_FILES = ("mod_api.json", "gradle.properties", "build.gradle", "build.gradle.kts")
NOT_SERVER_CODE_PREFIXES = ("core/src/test/",)


def sibling(name: str):
    """One of the scripts beside this one, so every script reads a diff the same way."""
    spec = importlib.util.spec_from_file_location(
        name.replace("-", "_"), Path(__file__).resolve().parent / f"{name}.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def reason_to_run(path: str) -> str | None:
    """Why this changed path can change a dedicated-server run, or None when it cannot."""
    if path.startswith(CLIENT_ONLY_EXCEPTIONS):
        return f"{path} is a test the dedicated servers run"
    if path.startswith(CLIENT_ONLY_PREFIXES):
        return None
    if "/" not in path and path.endswith(".md"):
        return None
    return f"{path} is not provably client-only"


def needed(repo: Path, base: str, head: str) -> tuple[bool, str]:
    """Whether the dedicated legs can see anything the diff changed, and the reason."""
    paths = sibling("affected-mods").changed_paths(repo, base, head)
    if not paths:
        return True, "the diff is empty, which is more likely a mistake than a no-op"
    for path in paths:
        reason = reason_to_run(path)
        if reason is not None:
            return True, reason
    return False, (
        f"all {len(paths)} changed paths are mod ports or prose, which the client legs of every"
        " loader exercise, and none reaches server code"
    )


def every_loader_reason(path: str) -> str | None:
    """Why this path runs every loader's leg on a pull request and a push, or None."""
    if path.startswith(NOT_SERVER_CODE_PREFIXES):
        return None
    if path.startswith(EVERY_LOADER_PREFIXES):
        return f"{path} is core code, a coverage test or the build every server leg runs"
    name = path.rsplit("/", 1)[-1]
    if "/" not in path and (path in EVERY_LOADER_FILES or name.startswith("settings.")):
        return f"{path} is the root build, which can change a loader's class bytes"
    return None


def loaders_for_diff(repo: Path, base: str, head: str) -> tuple[list[str], str]:
    """The loaders whose legs a pull request or a push needs, and the reason, for a readable diff."""
    paths = sibling("affected-mods").changed_paths(repo, base, head)
    everything = list(DISPATCH_LOADERS)
    if not paths:
        return everything, "the diff is empty, which is more likely a mistake than a no-op"
    shared = sibling("affected-loaders").shared_dirs(repo, head)
    chosen: dict[str, str] = {}
    for path in paths:
        parts = path.split("/")
        if reason := every_loader_reason(path):
            return everything, reason
        if parts[0] == LOADERS:
            if len(parts) < 3 or parts[1] not in DISPATCH_LOADERS:
                return everything, f"{path} names no loader with a dedicated server"
            chosen.setdefault(parts[1], f"{path} is the loader's own code")
        elif parts[0] == SHARED:
            owners = {
                loader: directory
                for loader, directories in shared.items()
                for directory in sorted(directories)
                if loader in DISPATCH_LOADERS and path.startswith(directory.rstrip("/") + "/")
            }
            if not owners:
                return everything, f"{path} is shared code no loader's build declares"
            for loader, directory in owners.items():
                chosen.setdefault(loader, f"{path} is compiled by the loader from {directory}")
    if not chosen:
        return [], (
            f"all {len(paths)} changed paths are outside every loader's server code (mods, core, "
            "scripts or prose): no server class's bytes change, so the carried-over exec data counts"
        )
    return sorted(chosen), "; ".join(f"{k}: {v}" for k, v in sorted(chosen.items()))


def answer(repo: Path, event: str, ref: str, base: str, head: str) -> tuple[bool, str]:
    """The answer for a dispatch (or any other event), and the reason for it."""
    if event != "workflow_dispatch":
        return True, f"a {event or 'non-dispatch'} run is not a base dispatch"
    if ref == "refs/heads/main":
        return True, "this dispatch is on main, which is the full run a release and the check send"
    if not base:
        return True, "this dispatch names no base, so it is the full run a release requires"
    return needed(repo, base, head)


def loaders(repo: Path, event: str, ref: str, base: str, head: str) -> tuple[list[str], str]:
    """The loaders whose dedicated legs run for this event, and the reason."""
    if event in ("pull_request", "push"):
        if not base:
            return list(
                DISPATCH_LOADERS
            ), f"this {event} names no base commit, so its diff cannot be read"
        return loaders_for_diff(repo, base, head)
    run, reason = answer(repo, event, ref, base, head)
    return (list(DISPATCH_LOADERS) if run else []), reason


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("base", help="the commit the diff is taken from")
    parser.add_argument("head", help="the commit that is built")
    parser.add_argument("--repo", default=".", help="the git repository (default: the current one)")
    parser.add_argument("--event", default="", help="github.event_name")
    parser.add_argument("--ref", default="", help="github.ref")
    parser.add_argument(
        "--loaders", action="store_true", help="print the JSON list of loaders instead"
    )
    args = parser.parse_args(argv)
    try:
        chosen, reason = loaders(Path(args.repo), args.event, args.ref, args.base, args.head)
    except Exception as e:  # noqa: BLE001 - whatever went wrong, the answer is to run the legs
        chosen, reason = list(DISPATCH_LOADERS), f"{type(e).__name__}: {e}"
    verdict = (
        f"running the dedicated-server legs of {', '.join(chosen)}"
        if chosen
        else "skipping the dedicated-server legs"
    )
    print(f"dedicated-needed: {verdict}: {reason}", file=sys.stderr)
    print(json.dumps(chosen) if args.loaders else ("true" if chosen else "false"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
