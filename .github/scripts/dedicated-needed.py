#!/usr/bin/env python3
"""Print whether a dispatch needs the dedicated-server legs (#1342), ``true`` or ``false``.

    dedicated-needed.py <base> <head> [--repo <dir>] [--event <event>] [--ref <ref>]

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
full runs the four-hourly check and the weekly release send. Every other event (a push, a pull
request, the schedule) answers ``true`` too, because the job's own condition already limits it to
a dispatch and this script is only asked about one.
"""

from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path

# A changed path under one of these is client-only unless CLIENT_ONLY_EXCEPTIONS names it.
CLIENT_ONLY_PREFIXES = ("mods/",)
# The mod whose tests are the server legs' own subject, so a change to it runs them.
CLIENT_ONLY_EXCEPTIONS = ("mods/coverage/",)


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


def answer(repo: Path, event: str, ref: str, base: str, head: str) -> tuple[bool, str]:
    """The answer for this event, and the reason for it."""
    if event != "workflow_dispatch":
        return True, f"a {event or 'non-dispatch'} run is not a base dispatch"
    if ref == "refs/heads/main":
        return True, "this dispatch is on main, which is the full run a release and the check send"
    if not base:
        return True, "this dispatch names no base, so it is the full run a release requires"
    return needed(repo, base, head)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("base", help="the commit the diff is taken from")
    parser.add_argument("head", help="the commit that is built")
    parser.add_argument("--repo", default=".", help="the git repository (default: the current one)")
    parser.add_argument("--event", default="", help="github.event_name")
    parser.add_argument("--ref", default="", help="github.ref")
    args = parser.parse_args(argv)
    try:
        run, reason = answer(Path(args.repo), args.event, args.ref, args.base, args.head)
    except Exception as e:  # noqa: BLE001 - whatever went wrong, the answer is to run the legs
        run, reason = True, f"{type(e).__name__}: {e}"
    verdict = "running" if run else "skipping"
    print(f"dedicated-needed: {verdict} the dedicated-server legs: {reason}", file=sys.stderr)
    print("true" if run else "false")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
