#!/usr/bin/env python3
"""Print the reference run's mod and runtime, read from the mod under test's about.json (#832).

    reference-selection.py <mod> [--repo <dir>]

The reference job no longer knows its own mod and loader. It reads them from the mod under
test's about.json, the first derived_from entry, which is the build the port recreates, and it
runs the reference harness on that entry's runtime with that entry's artifacts. This script is
that read. It prints the chosen entry to the CI log, so the run says which derived_from drove
it, and to stdout the values the job drives from:

    mod=<the mod under test, as given>
    loader=<the loaders/* directory the runtime names>
    java=<the runtime's Java version>

A mod whose about.json has no first derived_from entry, or whose first entry names no runtime,
is a clear failure: the reference job has nothing to run, and saying so beats silently running
the wrong mod. A runtime that names no loader the repository has, or a Java the loaders are not
tested on, fails the same way. Nothing here is a guess the job could paper over.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# The Java versions the loaders are tested on, so a runtime cannot quietly name another. The same
# list about_schema.py holds for the rule it enforces on the committed about.json files.
JAVA_VERSIONS = ("8", "17", "21")


def fail(message: str) -> int:
    """The clear failure: the reason goes to the log, and the exit code says no run happened."""
    print(f"FAILED: {message}", file=sys.stderr)
    return 1


def select(mod: str, repo: Path) -> int:
    """The mod under test's first derived_from entry, or a clear failure when there is none."""
    mod_dir = repo / "mods" / mod
    if not mod_dir.is_dir():
        return fail(f"mods/{mod} is not a mod directory, so the reference job has no mod to run.")

    about = mod_dir / "about.json"
    if not about.is_file():
        return fail(f"{mod} has no about.json, so it names no reference to run.")

    try:
        data = json.loads(about.read_text(encoding="utf-8"))
    except Exception as e:  # noqa: BLE001 - a file that does not parse names no runtime to read
        return fail(f"{mod}'s about.json does not parse: {e}")

    derived_from = data.get("derived_from")
    if not isinstance(derived_from, list) or not derived_from:
        return fail(
            f"{mod}'s about.json has no derived_from entry, so the reference job has nothing to"
            " run; a reference needs the mod's first derived_from, its runtime and artifacts."
        )

    entry = derived_from[0]
    if not isinstance(entry, dict):
        return fail(f"{mod}'s first derived_from entry is not an object that can name a runtime.")

    runtime = entry.get("runtime")
    if not isinstance(runtime, dict):
        return fail(
            f"{mod}'s first derived_from entry names no runtime for the reference to run on."
        )

    loader = runtime.get("loader")
    if not isinstance(loader, str) or not loader:
        return fail(
            f"{mod}'s first derived_from entry names no loader for the reference to run on."
        )
    if not (repo / "loaders" / loader).is_dir():
        return fail(
            f"{mod}'s runtime names the loader {loader!r}, which has no loaders/ directory to run on."
        )

    java = runtime.get("java")
    if not isinstance(java, str) or java not in JAVA_VERSIONS:
        return fail(
            f"{mod}'s runtime names Java {java!r}, which the loaders are not tested on;"
            f" it must be one of {', '.join(JAVA_VERSIONS)}."
        )

    # The log line: which derived_from entry drove the reference run, and on what runtime. It is
    # always the mod's first entry; the name and revision, when the entry carries them, say which.
    detail = ""
    name = entry.get("name")
    revision = entry.get("revision")
    if isinstance(name, str) and name:
        detail = name
        if isinstance(revision, str) and revision:
            detail += f" at {revision}"
    if detail:
        detail = f", {detail}"
    minecraft = runtime.get("minecraft")
    loader_version = runtime.get("loader_version")
    print(
        f"reference-selection: the reference job runs {mod} on {loader} (Java {java}), from"
        f" {mod}'s first derived_from entry{detail}: Minecraft {minecraft}, loader {loader},"
        f" loader version {loader_version}, Java {java}.",
        file=sys.stderr,
    )

    print(f"mod={mod}")
    print(f"loader={loader}")
    print(f"java={java}")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("mod", help="the mod under test, a directory under mods/")
    parser.add_argument(
        "--repo", default=".", help="the repository root (default: the current one)"
    )
    args = parser.parse_args(argv)
    return select(args.mod, Path(args.repo).resolve())


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
